import asyncio
import json

import httpx2
import pytest
from agents import MaxTurnsExceeded, ModelBehaviorError, ModelTracing, OpenAIResponsesModel
from agents.testing import ModelStep, ScriptedModel, assistant_message
from openai import AsyncOpenAI

from app.support_program_ranking.errors import AgentExecutionError
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import (
    SCORING_VERSION,
    ScoredSupportProgram,
    SupportProgramCandidate,
    SupportProgramRankingOutput,
    SupportProgramRankingRequest,
)
from app.support_program_ranking.prompt import (
    SUPPORT_PROGRAM_RANKING_INSTRUCTIONS,
)


def ranking_request() -> SupportProgramRankingRequest:
    return SupportProgramRankingRequest(
        originalQuery="서울 AI 창업기업 지원",
        scoringVersion=SCORING_VERSION,
        resultLimit=1,
        candidates=[
            SupportProgramCandidate(
                id="program-1",
                title="서울 AI 창업기업 사업화",
                organization="서울경제진흥원",
                summary="AI 창업기업의 사업화를 지원합니다.",
                categories=["AI", "창업"],
                regions=["서울"],
                targetDescription="서울 소재 창업기업",
                applicationPeriod="상시 접수",
                status="OPEN",
            )
        ],
    )


def valid_output() -> SupportProgramRankingOutput:
    return SupportProgramRankingOutput(
        rankings=[
            ScoredSupportProgram(
                programId="program-1",
                semanticRelevance=38,
                targetFit=24,
                regionFit=15,
                applicationStatusFit=10,
                supportTypeFit=8,
                totalScore=95,
                recommendationReasons=["서울 AI 창업기업 사업화 지원"],
            )
        ]
    )


@pytest.mark.anyio
async def test_runs_typed_ranking_agent_through_the_real_runner() -> None:
    expected = valid_output()
    model = ScriptedModel([[assistant_message(expected.model_dump_json(by_alias=True))]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    assert await agent.rank(ranking_request()) == expected

    call = model.first_call
    assert call is not None
    assert call.system_instructions == SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    request_json = json.loads(call.input[0]["content"])  # type: ignore[index]
    assert request_json["originalQuery"] == "서울 AI 창업기업 지원"
    assert request_json["candidates"][0]["id"] == "program-1"
    assert call.output_schema is not None
    assert call.output_schema.output_type is SupportProgramRankingOutput
    assert call.model_settings.timeout == 3.0
    assert call.tracing is ModelTracing.DISABLED
    model.assert_complete()


@pytest.mark.anyio
async def test_turns_invalid_structured_output_into_boundary_error() -> None:
    model = ScriptedModel([[assistant_message("not-json")]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=1.0,
        run_timeout_seconds=2.0,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(ranking_request())

    assert isinstance(captured.value.__cause__, ModelBehaviorError)


@pytest.mark.anyio
async def test_limits_ranking_to_one_model_turn() -> None:
    model = ScriptedModel(
        [[], [assistant_message(valid_output().model_dump_json(by_alias=True))]]
    )
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=1.0,
        run_timeout_seconds=2.0,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(ranking_request())

    assert isinstance(captured.value.__cause__, MaxTurnsExceeded)
    assert len(model.calls) == 1


@pytest.mark.anyio
async def test_enforces_whole_ranking_deadline() -> None:
    async def hang_forever(_: object) -> list[object]:
        await asyncio.Event().wait()
        return []

    model = ScriptedModel([ModelStep.respond(hang_forever)])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=1.0,
        run_timeout_seconds=0.01,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(ranking_request())

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
    agent = SupportProgramRecommendationAgent(
        model=OpenAIResponsesModel(
            model="gpt-5.6-luna",
            openai_client=openai_client,
        ),
        model_timeout_seconds=4.0,
        run_timeout_seconds=5.0,
    )

    try:
        assert await agent.rank(ranking_request()) == valid_output()
    finally:
        await openai_client.close()

    request_body = captured_requests[0]
    assert request_body["store"] is False
    assert request_body["max_output_tokens"] == 4_000
    assert request_body["reasoning"] == {"effort": "none"}
    text_format = request_body["text"]["format"]  # type: ignore[index]
    assert text_format["type"] == "json_schema"
    assert text_format["strict"] is True
    schema = text_format["schema"]
    assert schema["additionalProperties"] is False
    assert schema["required"] == ["rankings"]
