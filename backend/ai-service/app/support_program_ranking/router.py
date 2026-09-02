from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.support_program_ranking.errors import AgentExecutionError
from app.support_program_ranking.models import (
    SupportProgramRankingRequest,
    SupportProgramRankingResponse,
)
from app.support_program_ranking.service import SupportProgramRankingService


router = APIRouter(prefix="/internal/v1", tags=["internal"])


def get_support_program_ranking_service(
    request: Request,
) -> SupportProgramRankingService:
    return request.app.state.container.support_program_ranking_service


@router.post(
    "/support-program-rankings/rank",
    response_model=SupportProgramRankingResponse,
    summary="지원사업 후보 LLM 점수화",
)
async def rank_support_programs(
    payload: SupportProgramRankingRequest,
    service: Annotated[
        SupportProgramRankingService,
        Depends(get_support_program_ranking_service),
    ],
) -> SupportProgramRankingResponse:
    try:
        return await service.rank(payload)
    except AgentExecutionError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Support program ranking is temporarily unavailable.",
        ) from error
