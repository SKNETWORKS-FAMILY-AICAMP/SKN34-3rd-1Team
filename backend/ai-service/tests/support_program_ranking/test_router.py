import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.support_program_ranking.errors import AgentExecutionError
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import (
    SCORING_VERSION,
    ScoredSupportProgram,
    SupportProgramRankingOutput,
    SupportProgramRankingRequest,
)
from app.config import Settings
from app.main import create_app


TEST_SETTINGS = Settings(
    openai_api_key="test-key",
    openai_model="unused-model",
    llm_model_timeout_seconds=2.0,
    llm_run_timeout_seconds=2.5,
)


def score(program_id: str, semantic: int) -> ScoredSupportProgram:
    return ScoredSupportProgram(
        programId=program_id,
        semanticRelevance=semantic,
        targetFit=20,
        regionFit=10,
        applicationStatusFit=10,
        supportTypeFit=5,
        totalScore=semantic + 45,
        recommendationReasons=[f"{program_id} 근거"],
    )


class SuccessfulAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        self.requests: list[SupportProgramRankingRequest] = []

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        self.requests.append(request)
        return SupportProgramRankingOutput(
            rankings=[score("program-low", 20), score("program-high", 40)]
        )


class MissingCandidateAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(rankings=[score("program-high", 40)])


def request_body() -> dict[str, object]:
    return {
        "originalQuery": "서울 AI 창업기업 지원",
        "scoringVersion": SCORING_VERSION,
        "resultLimit": 2,
        "candidates": [
            {
                "id": "program-low",
                "title": "일반 창업 지원",
                "organization": "기관",
                "summary": "창업기업 지원",
                "categories": ["창업"],
                "regions": ["전국"],
                "targetDescription": "창업기업",
                "applicationPeriod": "상시 접수",
                "status": "OPEN",
            },
            {
                "id": "program-high",
                "title": "서울 AI 창업기업 지원",
                "organization": "기관",
                "summary": "서울 AI 기업 사업화 지원",
                "categories": ["AI", "창업"],
                "regions": ["서울"],
                "targetDescription": "서울 AI 창업기업",
                "applicationPeriod": "상시 접수",
                "status": "OPEN",
            },
        ],
    }


def test_returns_llm_scores_sorted_by_total_score() -> None:
    agent = SuccessfulAgent()
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=agent,
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=request_body(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["originalQuery"] == "서울 AI 창업기업 지원"
    assert body["scoringVersion"] == SCORING_VERSION
    assert [item["programId"] for item in body["rankings"]] == [
        "program-high",
        "program-low",
    ]
    assert body["rankings"][0]["totalScore"] == 85
    assert len(agent.requests) == 1


def test_rejects_an_agent_output_that_omits_a_candidate_without_leaking_details() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=MissingCandidateAgent(),
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=request_body(),
    )

    assert response.status_code == 503
    assert response.json() == {
        "detail": "Support program ranking is temporarily unavailable."
    }
    assert "program-high" not in response.text


@pytest.mark.parametrize(
    "mutation",
    [
        lambda body: body.pop("scoringVersion"),
        lambda body: body.update({"originalQuery": "   "}),
        lambda body: body.update({"scoringVersion": "stale-version"}),
        lambda body: body.update({"unknown": "value"}),
        lambda body: body["candidates"].append(body["candidates"][0]),
    ],
)
def test_rejects_invalid_requests(mutation) -> None:  # type: ignore[no-untyped-def]
    body = request_body()
    mutation(body)
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=SuccessfulAgent(),
        )
    )

    assert client.post(
        "/internal/v1/support-program-rankings/rank",
        json=body,
    ).status_code == 422


def test_score_schema_requires_the_total_to_equal_all_dimensions() -> None:
    with pytest.raises(ValidationError, match="totalScore"):
        ScoredSupportProgram(
            programId="program-1",
            semanticRelevance=40,
            targetFit=25,
            regionFit=15,
            applicationStatusFit=10,
            supportTypeFit=10,
            totalScore=99,
            recommendationReasons=["근거"],
        )
