from app.support_program_ranking.errors import AgentExecutionError

from .agent import SupportProgramRecommendationAgent
from .models import (
    SupportProgramRankingRequest,
    SupportProgramRankingResponse,
)


class SupportProgramRankingService:
    """Agent 결과의 후보 집합을 검증하고 점수순 상위 결과를 반환한다."""

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

        sorted_rankings = sorted(
            output.rankings,
            key=lambda ranking: (
                -ranking.total_score,
                candidate_order[ranking.program_id],
            ),
        )
        return SupportProgramRankingResponse(
            original_query=request.original_query,
            scoring_version=request.scoring_version,
            rankings=sorted_rankings[: request.result_limit],
        )
