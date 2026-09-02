import pytest
from agents.testing import ScriptedModel, assistant_message
from fastapi.testclient import TestClient

import app.bootstrap as bootstrap_module
import app.main as main_module
from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import (
    SCORING_VERSION,
    ScoredSupportProgram,
    SupportProgramCandidate,
    SupportProgramRankingOutput,
    SupportProgramRankingRequest,
)
from app.support_program_ranking.service import SupportProgramRankingService
from app.bootstrap import ApplicationContainer, build_application_container
from app.config import Settings


OPENAI_SETTINGS = Settings(
    openai_api_key="private-key",
    openai_model="test-model",
    llm_model_timeout_seconds=1.25,
    llm_run_timeout_seconds=1.75,
)


class FakeOpenAIClient:
    def __init__(self) -> None:
        self.closed = False

    async def close(self) -> None:
        self.closed = True


class NeverCalledAgent(SupportProgramRecommendationAgent):
    def __init__(self) -> None:
        pass

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        raise AssertionError("health and lifespan tests must not invoke the agent")


@pytest.mark.anyio
async def test_builds_and_wires_agent_in_the_composition_root(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured_client_arguments: dict[str, object] = {}
    captured_model_arguments: dict[str, object] = {}
    client = FakeOpenAIClient()
    expected = SupportProgramRankingOutput(
        rankings=[
            ScoredSupportProgram(
                programId="program-1",
                semanticRelevance=40,
                targetFit=25,
                regionFit=15,
                applicationStatusFit=10,
                supportTypeFit=10,
                totalScore=100,
                recommendationReasons=["질의와 직접 관련"],
            )
        ]
    )
    model = ScriptedModel(
        [[assistant_message(expected.model_dump_json(by_alias=True))]]
    )

    def fake_openai_client(**arguments: object) -> FakeOpenAIClient:
        captured_client_arguments.update(arguments)
        return client

    def fake_responses_model(**arguments: object) -> ScriptedModel:
        captured_model_arguments.update(arguments)
        return model

    monkeypatch.setattr(bootstrap_module, "AsyncOpenAI", fake_openai_client)
    monkeypatch.setattr(
        bootstrap_module,
        "OpenAIResponsesModel",
        fake_responses_model,
    )

    container = build_application_container(OPENAI_SETTINGS)

    assert isinstance(
        container.support_program_ranking_service,
        SupportProgramRankingService,
    )
    assert container.openai_client is client
    assert captured_client_arguments == {
        "api_key": "private-key",
        "timeout": 1.25,
        "max_retries": 0,
    }
    assert captured_model_arguments == {
        "model": "test-model",
        "openai_client": client,
    }

    response = await container.support_program_ranking_service.rank(
        SupportProgramRankingRequest(
            originalQuery="서울 AI 반도체",
            scoringVersion=SCORING_VERSION,
            resultLimit=1,
            candidates=[
                SupportProgramCandidate(
                    id="program-1",
                    title="서울 AI 반도체 지원",
                    organization="기관",
                    summary="반도체 지원",
                    categories=["AI"],
                    regions=["서울"],
                    targetDescription="중소기업",
                    applicationPeriod="상시 접수",
                    status="OPEN",
                )
            ],
        )
    )

    assert response.rankings[0].total_score == 100
    model.assert_complete()


def test_application_lifespan_closes_container_owned_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = FakeOpenAIClient()
    container = ApplicationContainer(
        support_program_ranking_service=SupportProgramRankingService(NeverCalledAgent()),
        openai_client=client,  # type: ignore[arg-type]
    )

    monkeypatch.setattr(
        main_module,
        "build_application_container",
        lambda *args, **kwargs: container,
    )

    with TestClient(main_module.create_app(settings=OPENAI_SETTINGS)) as test_client:
        assert client.closed is False
        assert test_client.get("/internal/v1/health").status_code == 200

    assert client.closed is True
