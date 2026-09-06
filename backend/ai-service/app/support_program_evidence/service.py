import asyncio
from hashlib import sha256
from math import isfinite
from uuid import NAMESPACE_URL, uuid5

from openai import AsyncOpenAI
from qdrant_client import AsyncQdrantClient, models

from app.support_program_embedding import prepare_embedding_inputs
from app.support_program_evidence.errors import SupportProgramEvidenceError
from app.support_program_evidence.models import (
    EvidenceChunkIdentity,
    SupportProgramEvidenceBatchRequest,
    SupportProgramEvidenceBatchResponse,
    SupportProgramEvidenceMatch,
    SupportProgramEvidenceSearchRequest,
    SupportProgramEvidenceSearchResponse,
)


class SupportProgramEvidenceService:
    """공고 상세 원문 청크를 별도 Qdrant collection에 색인하고 근거를 검색한다."""

    def __init__(
        self,
        openai_client: AsyncOpenAI,
        qdrant_client: AsyncQdrantClient,
        *,
        embedding_model: str,
        embedding_dimensions: int,
        embedding_timeout_seconds: float,
    ) -> None:
        self.openai_client = openai_client
        self.qdrant_client = qdrant_client
        self.embedding_model = embedding_model
        self.embedding_dimensions = embedding_dimensions
        self.embedding_timeout_seconds = embedding_timeout_seconds
        configuration_hash = sha256(
            f"{embedding_model}:{embedding_dimensions}:cl100k_base:8191".encode()
        ).hexdigest()[:16]
        self.collection_name = f"govbiz_support_program_evidence_v1_{configuration_hash}"
        self._write_lock = asyncio.Lock()

    async def index_chunks(
        self,
        request: SupportProgramEvidenceBatchRequest,
    ) -> SupportProgramEvidenceBatchResponse:
        try:
            async with asyncio.timeout(25), self._write_lock:
                await self._ensure_collection()
                identities = {_point_id(chunk): chunk for chunk in request.chunks}
                existing = await self.qdrant_client.retrieve(
                    collection_name=self.collection_name,
                    ids=list(identities),
                    with_payload=True,
                    with_vectors=False,
                )
                existing_ids: set[str] = set()
                for point in existing:
                    identity = identities.get(str(point.id))
                    if identity is None or not _payload_matches(point.payload, identity):
                        # 같은 chunk ID·해시를 다른 상세 공고에 재사용하면 안 된다.
                        raise SupportProgramEvidenceError()
                    existing_ids.add(str(point.id))
                missing = [
                    chunk
                    for point_id, chunk in identities.items()
                    if point_id not in existing_ids
                ]
                if missing:
                    vectors = await self._embed([chunk.text for chunk in missing])
                    points = [
                        models.PointStruct(
                            id=_point_id(chunk),
                            vector=vector,
                            payload={
                                "id": chunk.id,
                                "contentHash": chunk.content_hash,
                                "documentId": chunk.document_id,
                                "order": chunk.order,
                            },
                        )
                        for chunk, vector in zip(missing, vectors, strict=True)
                    ]
                    result = await self.qdrant_client.upsert(
                        collection_name=self.collection_name,
                        points=points,
                        wait=True,
                    )
                    if result.status != models.UpdateStatus.COMPLETED:
                        raise SupportProgramEvidenceError()
                return SupportProgramEvidenceBatchResponse(indexedCount=len(request.chunks))
        except SupportProgramEvidenceError:
            raise
        except Exception as error:
            raise SupportProgramEvidenceError() from error

    async def search(
        self,
        request: SupportProgramEvidenceSearchRequest,
    ) -> SupportProgramEvidenceSearchResponse:
        try:
            async with asyncio.timeout(25):
                if not await self.qdrant_client.collection_exists(self.collection_name):
                    raise SupportProgramEvidenceError("EVIDENCE_NOT_READY")
                identities = {
                    _point_id(chunk): chunk for chunk in request.eligible_chunks
                }
                point_ids = list(identities)
                await self._require_all_indexed(point_ids)
                indexed_points = await self.qdrant_client.retrieve(
                    collection_name=self.collection_name,
                    ids=point_ids,
                    with_payload=True,
                    with_vectors=False,
                )
                if len(indexed_points) != len(point_ids):
                    raise SupportProgramEvidenceError("EVIDENCE_NOT_READY")
                for point in indexed_points:
                    identity = identities.get(str(point.id))
                    if identity is None or not _payload_matches(point.payload, identity):
                        # 같은 ID·해시를 다른 documentId로 위장한 요청은 임베딩 전 차단한다.
                        raise SupportProgramEvidenceError()
                vector = (await self._embed([request.question]))[0]
                response = await self.qdrant_client.query_points(
                    collection_name=self.collection_name,
                    query=vector,
                    query_filter=models.Filter(
                        must=[models.HasIdCondition(has_id=point_ids)]
                    ),
                    limit=min(request.limit, len(point_ids)),
                    with_payload=True,
                    with_vectors=False,
                )
                matches: list[SupportProgramEvidenceMatch] = []
                seen: set[str] = set()
                for point in response.points:
                    point_id = str(point.id)
                    identity = identities.get(point_id)
                    if (
                        identity is None
                        or point_id in seen
                        or not _payload_matches(point.payload, identity)
                    ):
                        # HasId filter만 믿지 않고 문서 ID·청크 순서까지 다시 확인한다.
                        raise SupportProgramEvidenceError()
                    seen.add(point_id)
                    matches.append(
                        SupportProgramEvidenceMatch(
                            id=identity.id,
                            contentHash=identity.content_hash,
                            documentId=identity.document_id,
                            order=identity.order,
                            score=point.score,
                        )
                    )
                expected_count = min(request.limit, len(point_ids))
                if len(matches) != expected_count:
                    raise SupportProgramEvidenceError("EVIDENCE_NOT_READY")
                matches.sort(
                    key=lambda match: (
                        -match.score,
                        match.id,
                    )
                )
                return SupportProgramEvidenceSearchResponse(
                    question=request.question,
                    matches=matches,
                )
        except SupportProgramEvidenceError:
            raise
        except Exception as error:
            raise SupportProgramEvidenceError() from error

    async def _require_all_indexed(self, point_ids: list[str]) -> None:
        count = await self.qdrant_client.count(
            collection_name=self.collection_name,
            count_filter=models.Filter(
                must=[models.HasIdCondition(has_id=point_ids)]
            ),
            exact=True,
        )
        if count.count != len(point_ids):
            raise SupportProgramEvidenceError("EVIDENCE_NOT_READY")

    async def _ensure_collection(self) -> None:
        if not await self.qdrant_client.collection_exists(self.collection_name):
            await self.qdrant_client.create_collection(
                collection_name=self.collection_name,
                vectors_config=models.VectorParams(
                    size=self.embedding_dimensions,
                    distance=models.Distance.COSINE,
                ),
            )
        collection = await self.qdrant_client.get_collection(self.collection_name)
        vector_config = collection.config.params.vectors
        if (
            not isinstance(vector_config, models.VectorParams)
            or vector_config.size != self.embedding_dimensions
            or vector_config.distance != models.Distance.COSINE
        ):
            raise SupportProgramEvidenceError()

    async def _embed(self, texts: list[str]) -> list[list[float]]:
        inputs = await asyncio.to_thread(prepare_embedding_inputs, texts)
        vectors: list[list[float]] = []
        # 32 × 8191 < OpenAI 요청당 최대 300,000 tokens.
        for offset in range(0, len(inputs), 32):
            batch = inputs[offset : offset + 32]
            async with asyncio.timeout(self.embedding_timeout_seconds):
                raw_response = await self.openai_client.embeddings.with_raw_response.create(
                    model=self.embedding_model,
                    input=batch,
                    dimensions=self.embedding_dimensions,
                    encoding_format="float",
                    timeout=self.embedding_timeout_seconds,
                )
            response = raw_response.http_response.json()
            if (
                not isinstance(response, dict)
                or response.get("model") != self.embedding_model
            ):
                raise SupportProgramEvidenceError()
            data = response.get("data")
            if not isinstance(data, list) or len(data) != len(batch):
                raise SupportProgramEvidenceError()
            ordered: dict[int, list[float]] = {}
            for item in data:
                if not isinstance(item, dict):
                    raise SupportProgramEvidenceError()
                index = item.get("index")
                if (
                    type(index) is not int
                    or index not in range(len(batch))
                    or index in ordered
                ):
                    raise SupportProgramEvidenceError()
                vector = item.get("embedding")
                if (
                    not isinstance(vector, list)
                    or len(vector) != self.embedding_dimensions
                    or any(
                        isinstance(value, bool)
                        or not isinstance(value, (int, float))
                        or not isfinite(value)
                        for value in vector
                    )
                    or not any(value != 0 for value in vector)
                ):
                    raise SupportProgramEvidenceError()
                ordered[index] = vector
            vectors.extend(ordered[index] for index in range(len(batch)))
        return vectors


def _point_id(chunk: EvidenceChunkIdentity) -> str:
    return str(
        uuid5(
            NAMESPACE_URL,
            f"govbiz:support-program-evidence:v1:{chunk.id}:{chunk.content_hash}",
        )
    )


def _payload_matches(
    payload: dict | None,
    identity: EvidenceChunkIdentity,
) -> bool:
    return (
        payload is not None
        and payload.get("id") == identity.id
        and payload.get("contentHash") == identity.content_hash
        and payload.get("documentId") == identity.document_id
        and payload.get("order") == identity.order
    )
