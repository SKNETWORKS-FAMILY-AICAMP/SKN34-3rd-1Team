import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.support_program_ranking.errors import AgentExecutionError
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import (
    SCORING_VERSION,
    ScoredSupportProgram,
    SupportProgramEligibility,
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


def score(
    program_id: str,
    semantic: int,
    *,
    target: int = 20,
    target_eligibility: SupportProgramEligibility = SupportProgramEligibility.MATCH,
    region: int = 10,
    region_eligibility: SupportProgramEligibility = SupportProgramEligibility.MATCH,
    application_status: int = 10,
    support_type: int = 5,
) -> ScoredSupportProgram:
    return ScoredSupportProgram(
        programId=program_id,
        semanticRelevance=semantic,
        targetFit=target,
        targetEligibility=target_eligibility,
        regionFit=region,
        regionEligibility=region_eligibility,
        applicationStatusFit=application_status,
        supportTypeFit=support_type,
        totalScore=semantic + target + region + application_status + support_type,
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


class BelowSemanticMinimumAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(
            rankings=[score("program-low", 19), score("program-high", 40)]
        )


class BelowTotalMinimumAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(
            rankings=[
                score(
                    "program-low",
                    20,
                    target=10,
                    region=10,
                    application_status=10,
                    support_type=9,
                ),
                score("program-high", 40),
            ]
        )


class NoEligibleCandidateAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(
            rankings=[score("program-low", 0), score("program-high", 19)]
        )


class FixedOutputAgent(SupportProgramRecommendationAgent):
    def __init__(self, rankings: list[ScoredSupportProgram]) -> None:
        self._rankings = rankings

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(rankings=self._rankings)


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


def single_candidate_request_body(
    *,
    query: str,
    program_id: str,
    title: str,
    summary: str,
    regions: list[str],
    target_description: str,
) -> dict[str, object]:
    return {
        "originalQuery": query,
        "scoringVersion": SCORING_VERSION,
        "resultLimit": 1,
        "candidates": [
            {
                "id": program_id,
                "title": title,
                "organization": "기관",
                "summary": summary,
                "categories": ["AI"],
                "regions": regions,
                "targetDescription": target_description,
                "applicationPeriod": "상시 접수",
                "status": "OPEN",
            }
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
    assert body["rankings"][0]["targetEligibility"] == "MATCH"
    assert body["rankings"][0]["regionEligibility"] == "MATCH"
    assert len(agent.requests) == 1


def test_filters_a_candidate_below_the_semantic_relevance_minimum() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=BelowSemanticMinimumAgent(),
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=request_body(),
    )

    assert response.status_code == 200
    assert [item["programId"] for item in response.json()["rankings"]] == [
        "program-high"
    ]


def test_filters_a_candidate_below_the_total_score_minimum() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=BelowTotalMinimumAgent(),
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=request_body(),
    )

    assert response.status_code == 200
    assert [item["programId"] for item in response.json()["rankings"]] == [
        "program-high"
    ]


def test_returns_an_empty_ranking_when_no_candidate_meets_the_minimum() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=NoEligibleCandidateAgent(),
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=request_body(),
    )

    assert response.status_code == 200
    assert response.json()["rankings"] == []


def test_excludes_explicit_busan_region_mismatch_despite_high_score() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=FixedOutputAgent(
                [
                    score(
                        "program-busan",
                        40,
                        target=25,
                        region=0,
                        region_eligibility=SupportProgramEligibility.INCOMPATIBLE,
                        application_status=10,
                        support_type=10,
                    )
                ]
            ),
        )
    )
    body = single_candidate_request_body(
        query="서울 소재 AI 기업 지원",
        program_id="program-busan",
        title="부산 AI 기업 사업화 지원",
        summary="부산 소재 AI 기업의 사업화를 지원합니다.",
        regions=["부산"],
        target_description="부산 소재 중소기업",
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=body,
    )

    assert response.status_code == 200
    assert response.json()["rankings"] == []


def test_excludes_explicit_pre_startup_target_mismatch_despite_high_score() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=FixedOutputAgent(
                [
                    score(
                        "program-pre-startup",
                        40,
                        target=0,
                        target_eligibility=SupportProgramEligibility.INCOMPATIBLE,
                        region=15,
                        application_status=10,
                        support_type=10,
                    )
                ]
            ),
        )
    )
    body = single_candidate_request_body(
        query="서울 소재 기창업 AI 기업 지원",
        program_id="program-pre-startup",
        title="서울 AI 예비창업자 지원",
        summary="서울 예비창업자의 AI 사업화를 지원합니다.",
        regions=["서울"],
        target_description="예비창업자",
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=body,
    )

    assert response.status_code == 200
    assert response.json()["rankings"] == []


def test_keeps_a_candidate_when_target_and_region_information_are_unknown() -> None:
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=FixedOutputAgent(
                [
                    score(
                        "program-unknown",
                        40,
                        target=0,
                        target_eligibility=SupportProgramEligibility.UNKNOWN,
                        region=0,
                        region_eligibility=SupportProgramEligibility.UNKNOWN,
                        application_status=10,
                        support_type=10,
                    )
                ]
            ),
        )
    )
    body = single_candidate_request_body(
        query="AI 사업화 지원",
        program_id="program-unknown",
        title="AI 사업화 지원",
        summary="AI 기술 사업화를 지원합니다.",
        regions=["전국"],
        target_description="지원 대상은 공고문 참고",
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=body,
    )

    assert response.status_code == 200
    assert [item["programId"] for item in response.json()["rankings"]] == [
        "program-unknown"
    ]


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
            targetEligibility=SupportProgramEligibility.MATCH,
            regionFit=15,
            regionEligibility=SupportProgramEligibility.MATCH,
            applicationStatusFit=10,
            supportTypeFit=10,
            totalScore=99,
            recommendationReasons=["근거"],
        )


@pytest.mark.parametrize(
    ("target", "target_eligibility", "region", "region_eligibility", "message"),
    [
        (
            1,
            SupportProgramEligibility.INCOMPATIBLE,
            15,
            SupportProgramEligibility.MATCH,
            "incompatible target eligibility",
        ),
        (
            25,
            SupportProgramEligibility.MATCH,
            1,
            SupportProgramEligibility.INCOMPATIBLE,
            "incompatible region eligibility",
        ),
    ],
)
def test_score_schema_requires_zero_fit_for_explicit_incompatibility(
    target: int,
    target_eligibility: SupportProgramEligibility,
    region: int,
    region_eligibility: SupportProgramEligibility,
    message: str,
) -> None:
    with pytest.raises(ValidationError, match=message):
        score(
            "program-1",
            40,
            target=target,
            target_eligibility=target_eligibility,
            region=region,
            region_eligibility=region_eligibility,
        )
