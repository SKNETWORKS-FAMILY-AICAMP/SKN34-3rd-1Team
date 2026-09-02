from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.health.router import router as health_router
from app.support_program_ranking.router import router as support_program_rankings_router
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.bootstrap import build_application_container
from app.config import Settings


def create_app(
    *,
    settings: Settings | None = None,
    support_program_recommendation_agent: SupportProgramRecommendationAgent | None = None,
) -> FastAPI:
    """FastAPI 객체를 조립하는 애플리케이션 팩토리다."""
    container = build_application_container(
        settings or Settings.from_environment(),
        support_program_recommendation_agent=support_program_recommendation_agent,
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncGenerator[None]:
        try:
            yield
        finally:
            await application.state.container.close()

    application = FastAPI(
        title="GovBiz AI Service",
        description="GovBiz Core API가 내부에서 호출하는 AI 분석 서비스",
        version="0.1.0",
        lifespan=lifespan,
    )
    application.state.container = container
    application.include_router(health_router)
    application.include_router(support_program_rankings_router)
    return application
