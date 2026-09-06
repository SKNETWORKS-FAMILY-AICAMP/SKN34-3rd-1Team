import asyncio
import json

import httpx2
import pytest
from agents import MaxTurnsExceeded, ModelBehaviorError, ModelTracing, OpenAIResponsesModel
from agents.testing import ModelStep, ScriptedModel, assistant_message
from openai import AsyncOpenAI
from pydantic import ValidationError

from app.support_program_ranking.errors import AgentExecutionError
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import (
    SCORING_VERSION,
    AssessedSupportProgram,
    SupportProgramCandidate,
    SupportProgramRankingOutput,
    SupportProgramRankingRequest,
)
from app.support_program_ranking.prompt import (
    SUPPORT_PROGRAM_RANKING_INSTRUCTIONS,
)


def ranking_request(candidate_count: int = 1) -> SupportProgramRankingRequest:
    return SupportProgramRankingRequest(
        originalQuery="서울 AI 창업기업 지원",
        scoringVersion=SCORING_VERSION,
        resultLimit=min(candidate_count, 5),
        candidates=[
            SupportProgramCandidate(
                id=f"BIZINFO:program-{index}",
                title="서울 AI 창업기업 사업화",
                organization="서울경제진흥원",
                summary="AI 창업기업의 사업화를 지원합니다.",
                categories=["AI", "창업"],
                regions=["서울"],
                targetDescription="서울 소재 창업기업",
                applicationPeriod="상시 접수",
                status="OPEN",
            )
            for index in range(1, candidate_count + 1)
        ],
    )


def valid_output(candidate_count: int = 1) -> SupportProgramRankingOutput:
    return SupportProgramRankingOutput(
        rankings=[
            AssessedSupportProgram(
                programId=f"BIZINFO:program-{index}",
                semanticRelevance=38,
                targetAssessment={"eligibility": "MATCH", "score": 24},
                regionAssessment={"eligibility": "MATCH", "score": 15},
                applicationStatusFit=10,
                supportTypeFit=8,
                recommendationReasons=["서울 AI 창업기업 사업화 지원"],
            )
            for index in range(1, candidate_count + 1)
        ]
    )


def llm_output(candidate_count: int = 1) -> dict[str, object]:
    return {
        "rankings": {
            assessment.program_id: assessment.model_dump(by_alias=True, exclude={"program_id"})
            for assessment in valid_output(candidate_count).rankings
        }
    }


def llm_output_json(candidate_count: int = 1) -> str:
    return json.dumps(llm_output(candidate_count), ensure_ascii=False)


def rankings_schema(schema: dict[str, object]) -> dict[str, object]:
    reference = schema["properties"]["rankings"]["$ref"]
    return schema["$defs"][reference.split("/")[-1]]


def test_prompt_declares_the_recommendation_minimum_without_omitting_candidates() -> None:
    assert "semanticRelevance 20점 이상" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "totalScore 60점 이상" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "모든 후보를 점수화해야 합니다" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "targetAssessment" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "regionAssessment" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "totalScore는 출력하지 않습니다" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "정보 부족만으로 INCOMPATIBLE로 판단하지 마세요" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "candidates[].id" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "sourceCode:sourceProgramId" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "필수 키" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    assert "programId를 출력하지 않습니다" in SUPPORT_PROGRAM_RANKING_INSTRUCTIONS


@pytest.mark.anyio
async def test_runs_typed_ranking_agent_through_the_real_runner() -> None:
    expected = valid_output()
    model = ScriptedModel([[assistant_message(llm_output_json())]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    output = await agent.rank(ranking_request())
    assert isinstance(output, SupportProgramRankingOutput)
    assert output.model_dump() == expected.model_dump()

    call = model.first_call
    assert call is not None
    assert call.system_instructions == SUPPORT_PROGRAM_RANKING_INSTRUCTIONS
    request_json = json.loads(call.input[0]["content"])  # type: ignore[index]
    assert request_json["originalQuery"] == "서울 AI 창업기업 지원"
    assert request_json["candidates"][0]["id"] == "BIZINFO:program-1"
    assert call.output_schema is not None
    keyed_schema = rankings_schema(call.output_schema.json_schema())
    assert keyed_schema["type"] == "object"
    assert keyed_schema["required"] == ["BIZINFO:program-1"]
    assert keyed_schema["additionalProperties"] is False
    assert call.model_settings.timeout == 3.0
    assert call.tracing is ModelTracing.DISABLED
    model.assert_complete()


@pytest.mark.anyio
async def test_output_keys_are_bound_to_each_request_without_changing_the_shared_agent() -> None:
    counts = (1, 20, 1)
    model = ScriptedModel([
        [assistant_message(llm_output_json(count))]
        for count in counts
    ])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    for count in counts:
        output = await agent.rank(ranking_request(count))
        assert isinstance(output, SupportProgramRankingOutput)
        assert len(output.rankings) == count

    for call, count in zip(model.calls, counts, strict=True):
        assert call.output_schema is not None
        schema = rankings_schema(call.output_schema.json_schema())
        expected_ids = [candidate.id for candidate in ranking_request(count).candidates]
        assert list(schema["properties"]) == schema["required"] == expected_ids
        assert schema["additionalProperties"] is False
    assert agent._agent.output_type is None
    model.assert_complete()


@pytest.mark.anyio
async def test_rejects_nineteen_rankings_for_twenty_candidates_before_service_validation() -> None:
    model = ScriptedModel([[assistant_message(llm_output_json(19))]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(ranking_request(20))

    assert isinstance(captured.value.__cause__, ModelBehaviorError)
    assert len(model.calls) == 1


@pytest.mark.anyio
async def test_rejects_a_list_output_with_duplicate_ids() -> None:
    output = valid_output(20).model_dump(by_alias=True)
    output["rankings"][-1]["programId"] = output["rankings"][0]["programId"]
    model = ScriptedModel([[assistant_message(json.dumps(output, ensure_ascii=False))]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(ranking_request(20))

    assert isinstance(captured.value.__cause__, ModelBehaviorError)


@pytest.mark.anyio
async def test_actual_capture_duplicate_pattern_cannot_satisfy_all_required_keys() -> None:
    # actual-capture-v2 Q01: 20개를 반환했지만 다음 5개 ID가 중복되어 고유 ID는 15개였다.
    captured_ids = [
        "BIZINFO:PBLN_000000000125560", "BIZINFO:PBLN_000000000125166",
        "BIZINFO:PBLN_000000000118556", "BIZINFO:PBLN_000000000126060",
        "BIZINFO:PBLN_000000000121480", "BIZINFO:PBLN_000000000125603",
        "BIZINFO:PBLN_000000000125166", "BIZINFO:PBLN_000000000126060",
        "BIZINFO:PBLN_000000000121480", "BIZINFO:PBLN_000000000121288",
        "BIZINFO:PBLN_000000000121799", "BIZINFO:PBLN_000000000120474",
        "BIZINFO:PBLN_000000000125920", "BIZINFO:PBLN_000000000125603",
        "BIZINFO:PBLN_000000000124402", "BIZINFO:PBLN_000000000118556",
        "BIZINFO:PBLN_000000000122551", "BIZINFO:PBLN_000000000126164",
        "BIZINFO:PBLN_000000000125850", "BIZINFO:PBLN_000000000123203",
    ]
    missing_ids = [
        "BIZINFO:PBLN_000000000121635", "BIZINFO:PBLN_000000000125340",
        "BIZINFO:PBLN_000000000125877", "BIZINFO:PBLN_000000000126036",
        "BIZINFO:PBLN_000000000126161",
    ]
    expected_ids = list(dict.fromkeys(captured_ids)) + missing_ids
    assert len(captured_ids) == len(expected_ids) == 20
    assert len(set(captured_ids)) == 15
    request = ranking_request(20).model_dump(by_alias=True)
    for candidate, program_id in zip(request["candidates"], expected_ids, strict=True):
        candidate["id"] = program_id
    assessment = llm_output()["rankings"]["BIZINFO:program-1"]
    # 중복을 임의로 제거해도 필수 ID 5개가 없으므로 성공 결과가 될 수 없다.
    model = ScriptedModel([[assistant_message(json.dumps({
        "rankings": {program_id: assessment for program_id in captured_ids},
    }, ensure_ascii=False))]])
    agent = SupportProgramRecommendationAgent(
        model=model, model_timeout_seconds=3.0, run_timeout_seconds=4.0,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(SupportProgramRankingRequest.model_validate(request))

    assert isinstance(captured.value.__cause__, ModelBehaviorError)
    schema = rankings_schema(model.first_call.output_schema.json_schema())
    assert schema["required"] == expected_ids
    assert len(model.calls) == 1


@pytest.mark.anyio
@pytest.mark.parametrize("unexpected_id", ["BIZINFO:unexpected", "candidate_0"])
async def test_rejects_unrequested_keys_and_internal_field_names(unexpected_id: str) -> None:
    output = llm_output()
    output["rankings"][unexpected_id] = output["rankings"]["BIZINFO:program-1"]
    model = ScriptedModel([[assistant_message(json.dumps(output, ensure_ascii=False))]])
    agent = SupportProgramRecommendationAgent(
        model=model, model_timeout_seconds=3.0, run_timeout_seconds=4.0,
    )

    with pytest.raises(AgentExecutionError):
        await agent.rank(ranking_request())

    assert len(model.calls) == 1


@pytest.mark.anyio
async def test_preserves_qualified_ids_and_request_order_for_keyed_output() -> None:
    first_id = "BIZINFO:공고:한글/특수-1"
    second_id = "KSTARTUP:공고:한글/특수-1"
    request = ranking_request(2).model_dump(by_alias=True)
    request["candidates"][0]["id"] = first_id
    request["candidates"][1]["id"] = second_id
    assessment = llm_output()["rankings"]["BIZINFO:program-1"]
    model = ScriptedModel([[assistant_message(json.dumps({
        "rankings": {second_id: assessment, first_id: assessment},
    }, ensure_ascii=False))]])
    agent = SupportProgramRecommendationAgent(
        model=model, model_timeout_seconds=3.0, run_timeout_seconds=4.0,
    )

    result = await agent.rank(SupportProgramRankingRequest.model_validate(request))

    assert [item.program_id for item in result.rankings] == [first_id, second_id]
    assert rankings_schema(model.first_call.output_schema.json_schema())["required"] == [first_id, second_id]


def test_internal_output_still_rejects_duplicate_ids() -> None:
    output = valid_output(2).model_dump(by_alias=True)
    output["rankings"][1]["programId"] = output["rankings"][0]["programId"]

    with pytest.raises(ValidationError, match="ranked program ids must be unique"):
        SupportProgramRankingOutput.model_validate(output)


@pytest.mark.anyio
@pytest.mark.parametrize(
    "mutation",
    [
        lambda item: item.update(totalScore=95),
        lambda item: item.update(programId="BIZINFO:program-1"),
        lambda item: item.update(targetAssessment={"eligibility": "INCOMPATIBLE", "score": 4}),
        lambda item: item.update(regionAssessment={"eligibility": "INCOMPATIBLE", "score": 1}),
        lambda item: item.update(targetAssessment={"eligibility": "MATCH", "score": 26}),
        lambda item: item.update(regionAssessment={"eligibility": "UNKNOWN", "score": 16}),
        lambda item: item.update(targetAssessment={"eligibility": "UNKNOWN", "score": -1}),
        lambda item: item.update(regionAssessment={"eligibility": "ELIGIBLE", "score": 0}),
        lambda item: item.update(recommendationReasons=["  "]),
        lambda item: item.update(recommendationReasons=["가" * 121]),
    ],
)
async def test_rejects_invalid_assessments_without_normalizing_judgments_or_retrying(mutation) -> None:
    output = llm_output()
    mutation(output["rankings"]["BIZINFO:program-1"])
    model = ScriptedModel([[assistant_message(json.dumps(output, ensure_ascii=False))]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    with pytest.raises(AgentExecutionError) as captured:
        await agent.rank(ranking_request())

    assert isinstance(captured.value.__cause__, ModelBehaviorError)
    assert len(model.calls) == 1


@pytest.mark.anyio
@pytest.mark.parametrize("dimension", ["targetAssessment", "regionAssessment"])
async def test_preserves_incompatible_judgment_with_zero_score(dimension: str) -> None:
    output = llm_output()
    output["rankings"]["BIZINFO:program-1"][dimension] = {"eligibility": "INCOMPATIBLE", "score": 0}
    model = ScriptedModel([[assistant_message(json.dumps(output, ensure_ascii=False))]])
    agent = SupportProgramRecommendationAgent(
        model=model,
        model_timeout_seconds=3.0,
        run_timeout_seconds=4.0,
    )

    result = await agent.rank(ranking_request())

    assert result.model_dump(by_alias=True)["rankings"][0][dimension] == {
        "eligibility": "INCOMPATIBLE",
        "score": 0,
    }
    assert len(model.calls) == 1


def test_assessment_keeps_existing_reason_normalization() -> None:
    payload = valid_output().rankings[0].model_dump(by_alias=True)
    payload["recommendationReasons"] = ["  원문 근거  ", "원문 근거", "가" * 120]

    assessment = AssessedSupportProgram.model_validate(payload)

    assert assessment.recommendation_reasons == ["원문 근거", "가" * 120]


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
        [[], [assistant_message(llm_output_json())]]
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
@pytest.mark.parametrize("candidate_count", [1, 20])
async def test_openai_request_uses_non_stored_strict_structured_output(candidate_count: int) -> None:
    captured_requests: list[dict[str, object]] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        captured_requests.append(json.loads(request.content))
        return httpx2.Response(
            200,
            json=responses_body(llm_output_json(candidate_count)),
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
        output = await agent.rank(ranking_request(candidate_count))
        assert isinstance(output, SupportProgramRankingOutput)
        assert output.model_dump() == valid_output(candidate_count).model_dump()
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
    keyed_schema = rankings_schema(schema)
    expected_ids = [candidate.id for candidate in ranking_request(candidate_count).candidates]
    assert keyed_schema["type"] == "object"
    assert keyed_schema["required"] == list(keyed_schema["properties"]) == expected_ids
    assert keyed_schema["additionalProperties"] is False
    assessment_schema = schema["$defs"]["SupportProgramAssessment"]
    assert "totalScore" not in assessment_schema["properties"]
    assert "totalScore" not in assessment_schema["required"]
    assert "programId" not in assessment_schema["properties"]
    assert assessment_schema["additionalProperties"] is False
    for dimension, maximum in (("targetAssessment", 25), ("regionAssessment", 15)):
        branches = assessment_schema["properties"][dimension]["anyOf"]
        assert len(branches) == 2
        branch_schemas = [schema["$defs"][branch["$ref"].split("/")[-1]] for branch in branches]
        compatible, incompatible = branch_schemas
        assert compatible["properties"]["eligibility"]["enum"] == ["MATCH", "UNKNOWN"]
        assert compatible["properties"]["score"]["minimum"] == 0
        assert compatible["properties"]["score"]["maximum"] == maximum
        assert incompatible["properties"]["eligibility"]["const"] == "INCOMPATIBLE"
        assert incompatible["properties"]["score"]["const"] == 0
        for branch in branch_schemas:
            assert branch["required"] == ["eligibility", "score"]
            assert branch["additionalProperties"] is False
