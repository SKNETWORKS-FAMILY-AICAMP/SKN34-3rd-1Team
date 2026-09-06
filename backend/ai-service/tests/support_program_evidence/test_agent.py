import asyncio
import json
from hashlib import sha256

import httpx2
import pytest
from agents import MaxTurnsExceeded, ModelBehaviorError, ModelTracing, OpenAIResponsesModel
from agents.testing import ModelStep, ScriptedModel, assistant_message
from openai import AsyncOpenAI
from pydantic import ValidationError

from app.support_program_evidence.agent import SupportProgramEvidenceAnswerAgent
from app.support_program_evidence.errors import SupportProgramEvidenceError
from app.support_program_evidence.models import (
    SupportProgramEvidenceAnswerOutput,
    SupportProgramEvidenceAnswerRequest,
    SupportProgramEvidenceAnswerSelection,
    SupportProgramEvidenceAnswerStatus,
)
from app.support_program_evidence.prompt import (
    SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS,
)


def answer_request() -> SupportProgramEvidenceAnswerRequest:
    return SupportProgramEvidenceAnswerRequest(
        question="접수 기간이 언제인가요?",
        chunks=[
            {
                "id": sha256(b"evidence-chunk").hexdigest(),
                "documentId": "BIZINFO:PBLN:100",
                "order": 0,
                "text": "신청 접수 기간은 2026년 3월입니다.",
            }
        ],
    )


def valid_output() -> SupportProgramEvidenceAnswerOutput:
    return SupportProgramEvidenceAnswerOutput(
        answer="신청 접수 기간은 2026년 3월입니다.",
        answerStatus=SupportProgramEvidenceAnswerStatus.ANSWERED,
        citationChunkIds=[sha256(b"evidence-chunk").hexdigest()],
    )


def valid_selection() -> SupportProgramEvidenceAnswerSelection:
    return SupportProgramEvidenceAnswerSelection(
        answer=valid_output().answer,
        answerStatus="ANSWERED",
        citationChunkIndexes=[0],
    )


def test_prompt_requires_korean_evidence_only_answers_and_exact_citations() -> None:
    assert "한국어" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "외부 지식" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "INSUFFICIENT_EVIDENCE" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "chunks[].index" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "order를 인용 번호로 사용" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "지시·명령" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS


def test_answer_status_requires_consistent_unique_citations() -> None:
    chunk_id = sha256(b"evidence-chunk").hexdigest()
    with pytest.raises(ValidationError):
        SupportProgramEvidenceAnswerOutput(
            answer="근거가 있습니다.",
            answerStatus="ANSWERED",
            citationChunkIds=[],
        )
    with pytest.raises(ValidationError):
        SupportProgramEvidenceAnswerOutput(
            answer="근거가 부족합니다.",
            answerStatus="INSUFFICIENT_EVIDENCE",
            citationChunkIds=[chunk_id],
        )
    with pytest.raises(ValidationError):
        SupportProgramEvidenceAnswerOutput(
            answer="근거가 있습니다.",
            answerStatus="ANSWERED",
            citationChunkIds=[chunk_id, chunk_id],
        )


def test_prompt_requires_target_scope_and_preserves_condition_relationships() -> None:
    # Checks the instruction contract, not whether a live model actually follows it.
    instructions = SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "규모·업종·지역·업력·기업 형태" in instructions
    assert "제외·예외" in instructions
    assert "사업개요에 설명된 대상 범위" in instructions
    assert "우대 사항을 필수 자격으로 바꾸거나 본문에 없는 제한을 추가하지" in instructions
    assert "'모두 충족'과 '중 하나'의 관계를 유지" in instructions
    assert "최종 신청 가능 여부를 확정하지" in instructions
    assert "각 조건을 뒷받침하는 청크를 함께 인용" in instructions


@pytest.mark.anyio
async def test_runs_typed_evidence_answer_agent_through_the_real_runner() -> None:
    expected = valid_output()
    model = ScriptedModel([[assistant_message(valid_selection().model_dump_json(by_alias=True))]])
    agent = SupportProgramEvidenceAnswerAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    assert await agent.answer(answer_request()) == expected

    call = model.first_call
    assert call is not None
    assert call.system_instructions == SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    request_json = json.loads(call.input[0]["content"])  # type: ignore[index]
    assert request_json["question"] == "접수 기간이 언제인가요?"
    assert request_json["chunks"][0]["documentId"] == "BIZINFO:PBLN:100"
    assert set(request_json["chunks"][0]) == {"index", "documentId", "order", "text"}
    assert request_json["chunks"][0]["index"] == 0
    assert answer_request().chunks[0].id not in json.dumps(request_json)
    assert call.output_schema is not None
    assert call.output_schema.output_type is SupportProgramEvidenceAnswerSelection
    assert call.model_settings.timeout == 3.0
    assert call.tracing is ModelTracing.DISABLED
    model.assert_complete()


@pytest.mark.anyio
async def test_turns_invalid_structured_output_into_a_safe_boundary_error() -> None:
    model = ScriptedModel([[assistant_message("not-json")]])
    agent = SupportProgramEvidenceAnswerAgent(
        model=model,
        model_timeout_seconds=1.0,
        run_timeout_seconds=2.0,
    )

    with pytest.raises(SupportProgramEvidenceError):
        await agent.answer(answer_request())


@pytest.mark.anyio
async def test_limits_evidence_answering_to_one_model_turn() -> None:
    model = ScriptedModel([[], [assistant_message(valid_selection().model_dump_json(by_alias=True))]])
    agent = SupportProgramEvidenceAnswerAgent(
        model=model,
        model_timeout_seconds=1.0,
        run_timeout_seconds=2.0,
    )

    with pytest.raises(SupportProgramEvidenceError) as captured:
        await agent.answer(answer_request())

    assert isinstance(captured.value.__cause__, MaxTurnsExceeded)
    assert len(model.calls) == 1


@pytest.mark.anyio
async def test_enforces_whole_answering_deadline() -> None:
    async def hang_forever(_: object) -> list[object]:
        await asyncio.Event().wait()
        return []

    model = ScriptedModel([ModelStep.respond(hang_forever)])
    agent = SupportProgramEvidenceAnswerAgent(
        model=model,
        model_timeout_seconds=1.0,
        run_timeout_seconds=0.01,
    )

    with pytest.raises(SupportProgramEvidenceError) as captured:
        await agent.answer(answer_request())

    assert isinstance(captured.value.__cause__, TimeoutError)


def responses_body(output_json: str) -> dict[str, object]:
    return {
        "id": "resp_test",
        "created_at": 0,
        "error": None,
        "incomplete_details": None,
        "model": "gpt-5.6-luna",
        "object": "response",
        "output": [
            {
                "id": "msg_test",
                "content": [
                    {
                        "annotations": [],
                        "text": output_json,
                        "type": "output_text",
                    }
                ],
                "role": "assistant",
                "status": "completed",
                "type": "message",
            }
        ],
        "parallel_tool_calls": False,
        "status": "completed",
        "tool_choice": "none",
        "tools": [],
    }


@pytest.mark.anyio
async def test_openai_request_uses_non_stored_strict_structured_output() -> None:
    captured_requests: list[dict[str, object]] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        captured_requests.append(json.loads(request.content))
        return httpx2.Response(
            200,
            json=responses_body(valid_selection().model_dump_json(by_alias=True)),
        )

    http_client = httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    openai_client = AsyncOpenAI(
        api_key="test-api-key",
        base_url="https://openai.test/v1/",
        http_client=http_client,
        max_retries=0,
    )
    agent = SupportProgramEvidenceAnswerAgent(
        model=OpenAIResponsesModel(
            model="gpt-5.6-luna",
            openai_client=openai_client,
        ),
        model_timeout_seconds=4.0,
        run_timeout_seconds=5.0,
    )

    try:
        assert await agent.answer(answer_request()) == valid_output()
    finally:
        await openai_client.close()

    request_body = captured_requests[0]
    assert request_body["store"] is False
    assert request_body["max_output_tokens"] == 2_000
    assert request_body["reasoning"] == {"effort": "none"}
    text_format = request_body["text"]["format"]  # type: ignore[index]
    assert text_format["type"] == "json_schema"
    assert text_format["strict"] is True
    schema = text_format["schema"]
    assert schema["additionalProperties"] is False
    assert schema["required"] == ["answer", "answerStatus", "citationChunkIndexes"]
    assert schema["properties"]["citationChunkIndexes"]["items"] == {
        "type": "integer", "minimum": 0, "maximum": 4,
    }
    assert answer_request().chunks[0].id not in json.dumps(request_body)


@pytest.mark.anyio
@pytest.mark.parametrize("indexes,status", [
    ([-1], "ANSWERED"), ([5], "ANSWERED"), ([1], "ANSWERED"),
    ([True], "ANSWERED"), (["0"], "ANSWERED"), ([0.0], "ANSWERED"),
    ([0, 0], "ANSWERED"), ([], "ANSWERED"), ([0], "INSUFFICIENT_EVIDENCE"),
])
async def test_rejects_invalid_index_selections_without_a_fallback(indexes, status):
    model = ScriptedModel([[assistant_message(json.dumps({
        "answer": "신청 접수 기간은 2026년 3월입니다.",
        "answerStatus": status, "citationChunkIndexes": indexes,
    }))]])
    agent = SupportProgramEvidenceAnswerAgent(model=model, model_timeout_seconds=1, run_timeout_seconds=2)
    with pytest.raises(SupportProgramEvidenceError):
        await agent.answer(answer_request())
    assert len(model.calls) == 1


@pytest.mark.anyio
async def test_restores_the_full_hash_instead_of_asking_the_model_to_copy_64_characters():
    # E12's observed failure omitted the final character of this source hash.
    source_id = "5a1b144a8e191821adb49a19ac84c7aabdf7133e2324815f9fbe15010eefaa9a"
    request = answer_request()
    request = request.model_copy(update={"chunks": [request.chunks[0].model_copy(update={"id": source_id})]})
    with pytest.raises(ValidationError):
        SupportProgramEvidenceAnswerOutput(
            answer="근거에 있는 답변", answerStatus="ANSWERED", citationChunkIds=[source_id[:-1]],
        )
    model = ScriptedModel([[assistant_message(valid_selection().model_dump_json(by_alias=True))]])
    agent = SupportProgramEvidenceAnswerAgent(model=model, model_timeout_seconds=1, run_timeout_seconds=2)
    result = await agent.answer(request)
    assert result.citation_chunk_ids == [source_id]
    assert len(result.citation_chunk_ids[0]) == 64
    assert source_id not in json.dumps(model.first_call.input)

    legacy_model = ScriptedModel([[assistant_message(json.dumps({
        "answer": "근거에 있는 답변", "answerStatus": "ANSWERED", "citationChunkIds": [source_id[:-1]],
    }))]])
    legacy_agent = SupportProgramEvidenceAnswerAgent(model=legacy_model, model_timeout_seconds=1, run_timeout_seconds=2)
    with pytest.raises(SupportProgramEvidenceError):
        await legacy_agent.answer(request)


@pytest.mark.anyio
async def test_uses_request_positions_not_non_contiguous_source_orders():
    first = answer_request().chunks[0]
    request = answer_request().model_copy(update={"chunks": [
        first.model_copy(update={"order": 9}),
        first.model_copy(update={"id": sha256(b"second").hexdigest(), "order": 3}),
        first.model_copy(update={"id": sha256(b"third").hexdigest(), "order": 12}),
    ]})
    selection = valid_selection().model_copy(update={"citation_chunk_indexes": [2, 0]})
    model = ScriptedModel([[assistant_message(selection.model_dump_json(by_alias=True))]])
    agent = SupportProgramEvidenceAnswerAgent(model=model, model_timeout_seconds=1, run_timeout_seconds=2)
    result = await agent.answer(request)
    assert result.citation_chunk_ids == [request.chunks[2].id, request.chunks[0].id]
    payload = json.loads(model.first_call.input[0]["content"])
    assert [chunk["index"] for chunk in payload["chunks"]] == [0, 1, 2]
    assert [chunk["order"] for chunk in payload["chunks"]] == [9, 3, 12]


@pytest.mark.anyio
async def test_keeps_concurrent_request_index_mappings_isolated():
    arrived = 0
    both_arrived = asyncio.Event()

    async def answer_after_both_arrive(_: object):
        nonlocal arrived
        arrived += 1
        if arrived == 2:
            both_arrived.set()
        await both_arrived.wait()
        return [assistant_message(valid_selection().model_dump_json(by_alias=True))]

    model = ScriptedModel([ModelStep.respond(answer_after_both_arrive), ModelStep.respond(answer_after_both_arrive)])
    agent = SupportProgramEvidenceAnswerAgent(model=model, model_timeout_seconds=1, run_timeout_seconds=2)
    first = answer_request()
    second = first.model_copy(update={"chunks": [first.chunks[0].model_copy(update={
        "id": sha256(b"other-request").hexdigest(), "document_id": "BIZINFO:OTHER",
    })]})
    results = await asyncio.gather(agent.answer(first), agent.answer(second))
    assert results[0].citation_chunk_ids == [first.chunks[0].id]
    assert results[1].citation_chunk_ids == [second.chunks[0].id]


@pytest.mark.anyio
async def test_keeps_insufficient_evidence_without_any_citations():
    model = ScriptedModel([[assistant_message(json.dumps({
        "answer": "제공된 근거만으로는 확인할 수 없습니다.",
        "answerStatus": "INSUFFICIENT_EVIDENCE", "citationChunkIndexes": [],
    }))]])
    agent = SupportProgramEvidenceAnswerAgent(model=model, model_timeout_seconds=1, run_timeout_seconds=2)
    answer = await agent.answer(answer_request())
    assert answer.answer_status is SupportProgramEvidenceAnswerStatus.INSUFFICIENT_EVIDENCE
    assert answer.citation_chunk_ids == []


@pytest.mark.anyio
async def test_keeps_refusal_as_an_error_without_retrying():
    requests = []

    def handler(request):
        requests.append(request)
        body = responses_body("")
        body["output"][0]["content"] = [{"type": "refusal", "refusal": "cannot answer"}]
        return httpx2.Response(200, json=body)

    client = AsyncOpenAI(api_key="test-key", max_retries=0,
                         http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    agent = SupportProgramEvidenceAnswerAgent(
        model=OpenAIResponsesModel(model="gpt-5.6-luna", openai_client=client),
        model_timeout_seconds=1, run_timeout_seconds=2,
    )
    try:
        with pytest.raises(SupportProgramEvidenceError):
            await agent.answer(answer_request())
    finally:
        await client.close()
    assert len(requests) == 1
