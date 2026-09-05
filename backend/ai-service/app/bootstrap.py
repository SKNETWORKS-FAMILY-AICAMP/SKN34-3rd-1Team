from dataclasses import dataclass

from agents import OpenAIResponsesModel
from openai import AsyncOpenAI
from qdrant_client import AsyncQdrantClient

from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.service import SupportProgramRankingService
from app.config import Settings
from app.support_program_index.service import SupportProgramIndexService


@dataclass(slots=True)
class ApplicationContainer:
    """애플리케이션 객체 그래프와 그 객체가 소유한 자원."""

    support_program_ranking_service: SupportProgramRankingService
    openai_client: AsyncOpenAI | None = None
    support_program_index_service: SupportProgramIndexService | None = None
    qdrant_client: AsyncQdrantClient | None = None

    async def close(self) -> None:
        try:
            if self.qdrant_client is not None:
                await self.qdrant_client.close()
        finally:
            if self.openai_client is not None:
                await self.openai_client.close()


def build_application_container(
    settings: Settings,
    *,
    support_program_recommendation_agent: SupportProgramRecommendationAgent | None = None,
) -> ApplicationContainer:
    """환경설정과 선택적 테스트 대역을 실제 애플리케이션 객체로 조립한다."""

    openai_client = AsyncOpenAI(
        api_key=settings.openai_api_key,
        timeout=settings.llm_model_timeout_seconds,
        max_retries=0,
    )
    agent = support_program_recommendation_agent

    if agent is None:
        model = OpenAIResponsesModel(
            model=settings.openai_model,
            openai_client=openai_client,
        )
        agent = SupportProgramRecommendationAgent(
            model=model,
            model_timeout_seconds=settings.llm_model_timeout_seconds,
            run_timeout_seconds=settings.llm_run_timeout_seconds,
        )

    qdrant_client = AsyncQdrantClient(
        url=settings.qdrant_url,
        api_key=settings.qdrant_api_key,
        timeout=settings.qdrant_timeout_seconds,
        check_compatibility=False,
    )
    return ApplicationContainer(
        support_program_ranking_service=SupportProgramRankingService(agent),
        openai_client=openai_client,
        qdrant_client=qdrant_client,
        support_program_index_service=SupportProgramIndexService(
            openai_client,
            qdrant_client,
            embedding_model=settings.openai_embedding_model,
            embedding_dimensions=settings.openai_embedding_dimensions,
            embedding_timeout_seconds=settings.embedding_timeout_seconds,
        ),
    )
