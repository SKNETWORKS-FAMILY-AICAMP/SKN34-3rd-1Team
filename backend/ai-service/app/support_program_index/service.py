import asyncio
from hashlib import sha256
from math import isfinite
from uuid import NAMESPACE_URL, uuid5

import tiktoken
from openai import AsyncOpenAI
from qdrant_client import AsyncQdrantClient, models

from app.support_program_index.models import (
    IndexedDocumentIdentity,
    SupportProgramIndexBatchRequest,
    SupportProgramIndexBatchResponse,
    SupportProgramIndexMatch,
    SupportProgramIndexPruneRequest,
    SupportProgramIndexPruneResponse,
    SupportProgramIndexSearchRequest,
    SupportProgramIndexSearchResponse,
)


class SupportProgramIndexError(RuntimeError):
    """벡터 검색 경계의 안전한 오류. 외부 응답은 포함하지 않는다."""

    def __init__(self, code: str = "INDEX_UNAVAILABLE") -> None:
        super().__init__(code)
        self.code = code


class SupportProgramIndexService:
    """현재 공고 버전을 임베딩하고 Qdrant에서 관련 후보를 검색한다."""

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
        configuration_hash = sha256(f"{embedding_model}:{embedding_dimensions}:cl100k_base:8191".encode()).hexdigest()[:16]
        self.collection_name = f"govbiz_support_program_v1_{configuration_hash}"
        self._write_lock = asyncio.Lock()

    async def index_batch(self, request: SupportProgramIndexBatchRequest) -> SupportProgramIndexBatchResponse:
        try:
            async with asyncio.timeout(25), self._write_lock:
                await self._ensure_collection()
                identities = {_point_id(document): document for document in request.documents}
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
                        raise SupportProgramIndexError()
                    existing_ids.add(str(point.id))
                missing = [document for point_id, document in identities.items() if point_id not in existing_ids]
                if missing:
                    vectors = await self._embed([document.text for document in missing])
                    # 모든 임베딩을 검증한 후 저장한다. 이전 버전은 prune 성공 전까지 유지한다.
                    points = [
                        models.PointStruct(
                            id=_point_id(document),
                            vector=vector,
                            payload={
                                "id": document.id,
                                "contentHash": document.content_hash,
                                "sourceCode": document.id.split(":", 1)[0],
                            },
                        )
                        for document, vector in zip(missing, vectors, strict=True)
                    ]
                    result = await self.qdrant_client.upsert(
                        collection_name=self.collection_name, points=points, wait=True,
                    )
                    if result.status != models.UpdateStatus.COMPLETED:
                        raise SupportProgramIndexError()
                return SupportProgramIndexBatchResponse(indexedCount=len(request.documents))
        except SupportProgramIndexError:
            raise
        except Exception as error:
            raise SupportProgramIndexError() from error

    async def prune(self, request: SupportProgramIndexPruneRequest) -> SupportProgramIndexPruneResponse:
        try:
            async with asyncio.timeout(15), self._write_lock:
                exists = await self.qdrant_client.collection_exists(self.collection_name)
                if not exists:
                    if request.documents:
                        raise SupportProgramIndexError("INDEX_NOT_READY")
                    return SupportProgramIndexPruneResponse(retainedCount=0)
                point_ids = [_point_id(document) for document in request.documents]
                if point_ids:
                    await self._require_all_indexed(point_ids)
                # 현재 제공처만 정리한다. 다른 제공처와 다른 모델의 collection은 건드리지 않는다.
                deletion_filter = models.Filter(
                    must=[models.FieldCondition(key="sourceCode", match=models.MatchValue(value=request.source_code))],
                    must_not=[models.HasIdCondition(has_id=point_ids)] if point_ids else None,
                )
                result = await self.qdrant_client.delete(
                    collection_name=self.collection_name,
                    points_selector=models.FilterSelector(filter=deletion_filter),
                    wait=True,
                )
                if result.status != models.UpdateStatus.COMPLETED:
                    raise SupportProgramIndexError()
                return SupportProgramIndexPruneResponse(retainedCount=len(request.documents))
        except SupportProgramIndexError:
            raise
        except Exception as error:
            raise SupportProgramIndexError() from error

    async def search(self, request: SupportProgramIndexSearchRequest) -> SupportProgramIndexSearchResponse:
        if not request.eligible_documents:
            return SupportProgramIndexSearchResponse(query=request.query, matches=[])
        try:
            async with asyncio.timeout(25):
                if not await self.qdrant_client.collection_exists(self.collection_name):
                    raise SupportProgramIndexError("INDEX_NOT_READY")
                identities = {_point_id(document): document for document in request.eligible_documents}
                point_ids = list(identities)
                await self._require_all_indexed(point_ids)
                vector = (await self._embed([request.query]))[0]
                response = await self.qdrant_client.query_points(
                    collection_name=self.collection_name,
                    query=vector,
                    query_filter=models.Filter(must=[models.HasIdCondition(has_id=point_ids)]),
                    limit=min(request.limit, len(point_ids)),
                    with_payload=True,
                    with_vectors=False,
                )
                matches: list[SupportProgramIndexMatch] = []
                seen: set[str] = set()
                for point in response.points:
                    point_id = str(point.id)
                    identity = identities.get(point_id)
                    if identity is None or point_id in seen or not _payload_matches(point.payload, identity):
                        raise SupportProgramIndexError()
                    seen.add(point_id)
                    matches.append(SupportProgramIndexMatch(
                        id=identity.id, contentHash=identity.content_hash, score=point.score,
                    ))
                if len(matches) != min(request.limit, len(point_ids)):
                    raise SupportProgramIndexError("INDEX_NOT_READY")
                matches.sort(key=lambda match: (-match.score, match.id))
                return SupportProgramIndexSearchResponse(query=request.query, matches=matches)
        except SupportProgramIndexError:
            raise
        except Exception as error:
            raise SupportProgramIndexError() from error

    async def _require_all_indexed(self, point_ids: list[str]) -> None:
        count = await self.qdrant_client.count(
            collection_name=self.collection_name,
            count_filter=models.Filter(must=[models.HasIdCondition(has_id=point_ids)]),
            exact=True,
        )
        if count.count != len(point_ids):
            raise SupportProgramIndexError("INDEX_NOT_READY")

    async def _ensure_collection(self) -> None:
        if not await self.qdrant_client.collection_exists(self.collection_name):
            await self.qdrant_client.create_collection(
                collection_name=self.collection_name,
                vectors_config=models.VectorParams(size=self.embedding_dimensions, distance=models.Distance.COSINE),
            )
        collection = await self.qdrant_client.get_collection(self.collection_name)
        vector_config = collection.config.params.vectors
        if not isinstance(vector_config, models.VectorParams) or vector_config.size != self.embedding_dimensions or vector_config.distance != models.Distance.COSINE:
            raise SupportProgramIndexError()

    async def _embed(self, texts: list[str]) -> list[list[float]]:
        encoding = await asyncio.to_thread(tiktoken.get_encoding, "cl100k_base")
        inputs: list[str] = []
        for text in texts:
            tokens = encoding.encode_ordinary(text)
            if len(tokens) > 8191:
                text = encoding.decode(tokens[:8191])
                while len(encoding.encode_ordinary(text)) > 8191:
                    text = text[:-1]
            inputs.append(text)
        vectors: list[list[float]] = []
        # 32 × 8191 < OpenAI 요청당 최대 300,000 tokens.
        for offset in range(0, len(inputs), 32):
            batch = inputs[offset:offset + 32]
            async with asyncio.timeout(self.embedding_timeout_seconds):
                raw_response = await self.openai_client.embeddings.with_raw_response.create(
                    model=self.embedding_model,
                    input=batch,
                    dimensions=self.embedding_dimensions,
                    encoding_format="float",
                    timeout=self.embedding_timeout_seconds,
                )
            # SDK의 float 강제 변환 전 원문 JSON을 검증해 bool·문자열을 숫자로 허용하지 않는다.
            response = raw_response.http_response.json()
            if not isinstance(response, dict) or response.get("model") != self.embedding_model:
                raise SupportProgramIndexError()
            data = response.get("data")
            if not isinstance(data, list) or len(data) != len(batch):
                raise SupportProgramIndexError()
            ordered: dict[int, list[float]] = {}
            for item in data:
                if not isinstance(item, dict):
                    raise SupportProgramIndexError()
                index = item.get("index")
                if type(index) is not int or index not in range(len(batch)) or index in ordered:
                    raise SupportProgramIndexError()
                vector = item.get("embedding")
                if not isinstance(vector, list) or len(vector) != self.embedding_dimensions or any(
                    isinstance(value, bool) or not isinstance(value, (int, float)) or not isfinite(value)
                    for value in vector
                ) or not any(value != 0 for value in vector):
                    raise SupportProgramIndexError()
                ordered[index] = vector
            vectors.extend(ordered[index] for index in range(len(batch)))
        return vectors


def _point_id(document: IndexedDocumentIdentity) -> str:
    return str(uuid5(NAMESPACE_URL, f"govbiz:document:v1:{document.id}:{document.content_hash}"))


def _payload_matches(payload: dict | None, identity: IndexedDocumentIdentity) -> bool:
    return payload is not None and payload.get("id") == identity.id and payload.get("contentHash") == identity.content_hash and payload.get("sourceCode") == identity.id.split(":", 1)[0]
