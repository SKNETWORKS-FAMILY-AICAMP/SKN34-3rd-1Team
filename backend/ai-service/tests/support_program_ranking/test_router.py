import json

import pytest
from agents.testing import ScriptedModel, assistant_message
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.support_program_ranking.errors import AgentExecutionError
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import (
    SCORING_VERSION,
    AssessedSupportProgram,
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
) -> AssessedSupportProgram:
    return AssessedSupportProgram(
        programId=program_id,
        semanticRelevance=semantic,
        targetAssessment={"eligibility": target_eligibility, "score": target},
        regionAssessment={"eligibility": region_eligibility, "score": region},
        applicationStatusFit=application_status,
        supportTypeFit=support_type,
        recommendationReasons=["공고 원문 근거"],
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
            rankings=[score("BIZINFO:program-low", 20), score("BIZINFO:program-high", 40)]
        )


class MissingCandidateAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(rankings=[score("BIZINFO:program-high", 40)])


class BelowSemanticMinimumAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        return SupportProgramRankingOutput(
            rankings=[score("BIZINFO:program-low", 19), score("BIZINFO:program-high", 40)]
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
                    "BIZINFO:program-low",
                    20,
                    target=10,
                    region=10,
                    application_status=10,
                    support_type=9,
                ),
                score("BIZINFO:program-high", 40),
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
            rankings=[score("BIZINFO:program-low", 0), score("BIZINFO:program-high", 19)]
        )


class FixedOutputAgent(SupportProgramRecommendationAgent):
    def __init__(self, rankings: list[AssessedSupportProgram]) -> None:
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
                "id": "BIZINFO:program-low",
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
                "id": "BIZINFO:program-high",
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
        "BIZINFO:program-high",
        "BIZINFO:program-low",
    ]
    assert body["rankings"][0]["totalScore"] == 85
    assert body["rankings"][0]["targetEligibility"] == "MATCH"
    assert body["rankings"][0]["regionEligibility"] == "MATCH"
    assert "targetAssessment" not in body["rankings"][0]
    assert "regionAssessment" not in body["rankings"][0]
    assert len(agent.requests) == 1


def test_computes_the_failed_capture_sum_in_service_and_keeps_http_contract() -> None:
    # 실제 캡처에서 24 + 25 + 15 + 10 + 7을 80으로 응답했던 회귀 사례.
    output = SupportProgramRankingOutput(
        rankings=[
            score(
                "BIZINFO:program-low",
                24,
                target=25,
                region=15,
                application_status=10,
                support_type=7,
            ),
            # 실제 캡처에서 10 + 8 + 15 + 3 + 2를 28로 응답했던 저관련성 사례.
            score(
                "BIZINFO:program-high",
                10,
                target=8,
                target_eligibility=SupportProgramEligibility.UNKNOWN,
                region=15,
                application_status=3,
                support_type=2,
            ),
        ]
    )
    llm_output = json.dumps({
        "rankings": {
            assessment.program_id: assessment.model_dump(by_alias=True, exclude={"program_id"})
            for assessment in output.rankings
        }
    }, ensure_ascii=False)
    assert "totalScore" not in llm_output
    model = ScriptedModel([[assistant_message(llm_output)]])
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=SupportProgramRecommendationAgent(
                model=model,
                model_timeout_seconds=2.0,
                run_timeout_seconds=2.5,
            ),
        )
    )

    response = client.post("/internal/v1/support-program-rankings/rank", json=request_body())

    assert response.status_code == 200
    assert response.json() == {
        "originalQuery": "서울 AI 창업기업 지원",
        "scoringVersion": SCORING_VERSION,
        "rankings": [{
            "programId": "BIZINFO:program-low",
            "semanticRelevance": 24,
            "targetFit": 25,
            "targetEligibility": "MATCH",
            "regionFit": 15,
            "regionEligibility": "MATCH",
            "applicationStatusFit": 10,
            "supportTypeFit": 7,
            "totalScore": 81,
            "recommendationReasons": ["공고 원문 근거"],
        }],
    }
    assert len(model.calls) == 1
    model.assert_complete()


def test_keeps_input_order_for_ties_and_applies_result_limit() -> None:
    body = request_body()
    body["resultLimit"] = 1
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=FixedOutputAgent([
                score("BIZINFO:program-high", 40),
                score("BIZINFO:program-low", 40),
            ]),
        )
    )

    response = client.post("/internal/v1/support-program-rankings/rank", json=body)

    assert response.status_code == 200
    assert [item["programId"] for item in response.json()["rankings"]] == ["BIZINFO:program-low"]


@pytest.mark.parametrize("dimension", ["targetAssessment", "regionAssessment"])
def test_invalid_llm_eligibility_returns_503_without_retry_or_fallback(dimension: str) -> None:
    output = SupportProgramRankingOutput(rankings=[
        score("BIZINFO:program-low", 40), score("BIZINFO:program-high", 40),
    ])
    payload = {"rankings": {
        assessment.program_id: assessment.model_dump(by_alias=True, exclude={"program_id"})
        for assessment in output.rankings
    }}
    payload["rankings"]["BIZINFO:program-low"][dimension] = {"eligibility": "INCOMPATIBLE", "score": 4}
    model = ScriptedModel([[assistant_message(json.dumps(payload, ensure_ascii=False))]])
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=SupportProgramRecommendationAgent(
                model=model,
                model_timeout_seconds=2.0,
                run_timeout_seconds=2.5,
            ),
        )
    )

    response = client.post("/internal/v1/support-program-rankings/rank", json=request_body())

    assert response.status_code == 503
    assert response.json() == {"detail": "Support program ranking is temporarily unavailable."}
    assert len(model.calls) == 1


def test_keeps_candidates_with_the_same_original_id_from_different_sources_distinct() -> None:
    body = request_body()
    body["candidates"][0]["id"] = "BIZINFO:PBLN_001"  # type: ignore[index]
    body["candidates"][1]["id"] = "KSTARTUP:PBLN_001"  # type: ignore[index]
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=FixedOutputAgent(
                [
                    score("KSTARTUP:PBLN_001", 40),
                    score("BIZINFO:PBLN_001", 20),
                ]
            ),
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=body,
    )

    assert response.status_code == 200
    assert [item["programId"] for item in response.json()["rankings"]] == [
        "KSTARTUP:PBLN_001",
        "BIZINFO:PBLN_001",
    ]


def test_accepts_the_maximum_length_canonical_program_id() -> None:
    canonical_id = f"{'S' * 64}:{'P' * 255}"
    body = request_body()
    body["resultLimit"] = 1
    body["candidates"] = [body["candidates"][0]]  # type: ignore[index]
    body["candidates"][0]["id"] = canonical_id  # type: ignore[index]
    client = TestClient(
        create_app(
            settings=TEST_SETTINGS,
            support_program_recommendation_agent=FixedOutputAgent([score(canonical_id, 40)]),
        )
    )

    response = client.post(
        "/internal/v1/support-program-rankings/rank",
        json=body,
    )

    assert len(canonical_id) == 320
    assert response.status_code == 200
    assert response.json()["rankings"][0]["programId"] == canonical_id


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
        "BIZINFO:program-high"
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
        "BIZINFO:program-high"
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
                        "BIZINFO:program-busan",
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
        program_id="BIZINFO:program-busan",
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
                        "BIZINFO:program-pre-startup",
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
        program_id="BIZINFO:program-pre-startup",
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
                        "BIZINFO:program-unknown",
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
        program_id="BIZINFO:program-unknown",
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
        "BIZINFO:program-unknown"
    ]
    assert response.json()["rankings"][0]["targetEligibility"] == "UNKNOWN"
    assert response.json()["rankings"][0]["regionEligibility"] == "UNKNOWN"


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
        lambda body: body["candidates"][0].update({"id": "PBLN_001"}),
        lambda body: body["candidates"][0].update({"id": "BIZINFO: PBLN_001"}),
        lambda body: body["candidates"][0].update({"id": "BIZINFO:PBLN_001 "}),
        lambda body: body["candidates"][0].update({"id": "BIZINFO:PBLN\u200b_001"}),
        lambda body: body["candidates"][0].update({"id": f"BIZINFO:{'P' * 256}"}),
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
            programId="BIZINFO:program-1",
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
    "program_id",
    [
        "BIZINFO:",
        "BIZINFO: PBLN_001",
        "BIZINFO:PBLN_001 ",
        "BIZINFO:PBLN\u0000_001",
        "BIZINFO:PBLN\u200b_001",
        f"BIZINFO:{'P' * 256}",
    ],
)
def test_score_schema_requires_a_canonical_program_id(program_id: str) -> None:
    with pytest.raises(ValidationError, match="canonical sourceCode:sourceProgramId"):
        score(program_id, 40)


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
        ScoredSupportProgram(
            programId="BIZINFO:program-1",
            semanticRelevance=40,
            targetFit=target,
            targetEligibility=target_eligibility,
            regionFit=region,
            regionEligibility=region_eligibility,
            applicationStatusFit=10,
            supportTypeFit=5,
            totalScore=40 + target + region + 10 + 5,
            recommendationReasons=["공고 원문 근거"],
        )
