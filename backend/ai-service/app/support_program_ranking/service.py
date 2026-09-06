from pydantic import ValidationError

from app.support_program_ranking.errors import AgentExecutionError

from .agent import SupportProgramRecommendationAgent
from .models import (
    ScoredSupportProgram,
    SupportProgramRankingRequest,
    SupportProgramRankingResponse,
    SupportProgramEligibility,
)


# 40점인 의미 관련성 항목의 절반과 100점 총점의 60%를 동시에 충족해야 추천한다.
MIN_SEMANTIC_RELEVANCE_SCORE = 20
MIN_TOTAL_RECOMMENDATION_SCORE = 60


class SupportProgramRankingService:
    """Agent 결과를 검증하고 적격 공고만 점수순으로 반환한다."""

    def __init__(self, agent: SupportProgramRecommendationAgent) -> None:
        self._agent = agent

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingResponse:
        output = await self._agent.rank(request)
        candidate_order = {
            candidate.id: index for index, candidate in enumerate(request.candidates)
        }
        expected_ids = set(candidate_order)
        actual_ids = {ranking.program_id for ranking in output.rankings}
        if actual_ids != expected_ids or len(output.rankings) != len(request.candidates):
            raise AgentExecutionError(
                "Support program recommendation agent changed the candidate id set"
            )

        try:
            scored_rankings = [
                ScoredSupportProgram(
                    program_id=assessment.program_id,
                    semantic_relevance=assessment.semantic_relevance,
                    target_fit=assessment.target_assessment.score,
                    target_eligibility=assessment.target_assessment.eligibility,
                    region_fit=assessment.region_assessment.score,
                    region_eligibility=assessment.region_assessment.eligibility,
                    application_status_fit=assessment.application_status_fit,
                    support_type_fit=assessment.support_type_fit,
                    total_score=(
                        assessment.semantic_relevance
                        + assessment.target_assessment.score
                        + assessment.region_assessment.score
                        + assessment.application_status_fit
                        + assessment.support_type_fit
                    ),
                    recommendation_reasons=assessment.recommendation_reasons,
                )
                for assessment in output.rankings
            ]
        except ValidationError as error:
            raise AgentExecutionError(
                "Support program recommendation agent produced invalid score dimensions"
            ) from error

        sorted_rankings = sorted(
            scored_rankings,
            key=lambda ranking: (
                -ranking.total_score,
                candidate_order[ranking.program_id],
            ),
        )
        eligible_rankings = [
            ranking
            for ranking in sorted_rankings
            if ranking.semantic_relevance >= MIN_SEMANTIC_RELEVANCE_SCORE
            and ranking.total_score >= MIN_TOTAL_RECOMMENDATION_SCORE
            and ranking.target_eligibility is not SupportProgramEligibility.INCOMPATIBLE
            and ranking.region_eligibility is not SupportProgramEligibility.INCOMPATIBLE
        ]
        return SupportProgramRankingResponse(
            original_query=request.original_query,
            scoring_version=request.scoring_version,
            rankings=eligible_rankings[: request.result_limit],
        )
