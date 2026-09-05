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


def test_prompt_requires_korean_evidence_only_answers_and_exact_citations() -> None:
    assert "한국어" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "외부 지식" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "INSUFFICIENT_EVIDENCE" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
    assert "chunks[].id" in SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS
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


@pytest.mark.anyio
async def test_runs_typed_evidence_answer_agent_through_the_real_runner() -> None:
    expected = valid_output()
    model = ScriptedModel([[assistant_message(expected.model_dump_json(by_alias=True))]])
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
    assert call.output_schema is not None
    assert call.output_schema.output_type is SupportProgramEvidenceAnswerOutput
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
    model = ScriptedModel([[], [assistant_message(valid_output().model_dump_json(by_alias=True))]])
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
            json=responses_body(valid_output().model_dump_json(by_alias=True)),
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
    assert schema["required"] == ["answer", "answerStatus", "citationChunkIds"]
