from dataclasses import dataclass

from agents import OpenAIResponsesModel
from openai import AsyncOpenAI

from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.service import SupportProgramRankingService
from app.config import Settings


@dataclass(slots=True)
class ApplicationContainer:
    """애플리케이션 객체 그래프와 그 객체가 소유한 자원."""

    support_program_ranking_service: SupportProgramRankingService
    openai_client: AsyncOpenAI | None = None

    async def close(self) -> None:
        if self.openai_client is not None:
            await self.openai_client.close()


def build_application_container(
    settings: Settings,
    *,
    support_program_recommendation_agent: SupportProgramRecommendationAgent | None = None,
) -> ApplicationContainer:
    """환경설정과 선택적 테스트 대역을 실제 애플리케이션 객체로 조립한다."""

    openai_client: AsyncOpenAI | None = None
    agent = support_program_recommendation_agent

    if agent is None:
        openai_client = AsyncOpenAI(
            api_key=settings.openai_api_key,
            timeout=settings.llm_model_timeout_seconds,
            max_retries=0,
        )
        model = OpenAIResponsesModel(
            model=settings.openai_model,
            openai_client=openai_client,
        )
        agent = SupportProgramRecommendationAgent(
            model=model,
            model_timeout_seconds=settings.llm_model_timeout_seconds,
            run_timeout_seconds=settings.llm_run_timeout_seconds,
        )

    return ApplicationContainer(
        support_program_ranking_service=SupportProgramRankingService(agent),
        openai_client=openai_client,
    )
