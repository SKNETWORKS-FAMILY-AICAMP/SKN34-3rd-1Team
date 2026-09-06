import importlib.util
import json
from io import BytesIO
from pathlib import Path

import httpx2
import pytest
from agents import OpenAIResponsesModel
from openai import AsyncOpenAI

from app.support_program_ranking.agent import SupportProgramRecommendationAgent
from app.support_program_ranking.models import SCORING_VERSION, SupportProgramRankingRequest
from app.support_program_ranking.service import SupportProgramRankingService


@pytest.mark.anyio
@pytest.mark.parametrize("candidate_count", [1, 20])
@pytest.mark.parametrize("has_relevant_candidate", [True, False])
async def test_compose_stub_matches_the_production_ranking_contract(
    monkeypatch: pytest.MonkeyPatch,
    candidate_count: int,
    has_relevant_candidate: bool,
) -> None:
    stub_path = Path(__file__).resolve().parents[4] / "infrastructure/stubs/openai/server.py"
    spec = importlib.util.spec_from_file_location("compose_openai_stub", stub_path)
    assert spec is not None and spec.loader is not None
    stub = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stub)
    request = SupportProgramRankingRequest.model_validate({
        "originalQuery": "서울 AI 지원",
        "scoringVersion": SCORING_VERSION,
        "resultLimit": min(candidate_count, 5),
        "candidates": [
            {
                "id": f"BIZINFO:공고:{index}/원본",
                "title": "서울 AI 지원" if has_relevant_candidate and index == candidate_count - 1 else "수출 지원",
                "organization": "테스트 기관",
                "summary": "지원사업 안내",
                "categories": [],
                "regions": ["서울"],
                "targetDescription": "중소기업",
                "applicationPeriod": "상시 접수",
                "status": "OPEN",
            }
            for index in range(candidate_count)
        ],
    })
    captured_responses: list[dict] = []

    def handle_http(http_request: httpx2.Request) -> httpx2.Response:
        # Run the actual Compose handler without opening a socket or sending any API request.
        handler = object.__new__(stub.Handler)
        handler.path = http_request.url.path
        handler.headers = {"Content-Length": str(len(http_request.content))}
        handler.rfile = BytesIO(http_request.content)
        responses: list[httpx2.Response] = []

        def respond(status: int, body: dict) -> None:
            captured_responses.append(body)
            responses.append(httpx2.Response(status, json=body))

        monkeypatch.setattr(handler, "respond", respond)
        handler.do_POST()
        assert len(responses) == 1
        return responses[0]

    openai_client = AsyncOpenAI(
        api_key="test-compose-key-never-sent",
        base_url="https://openai.test/v1/",
        http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(handle_http)),
        max_retries=0,
    )
    agent = SupportProgramRecommendationAgent(
        model=OpenAIResponsesModel(model="gpt-5.6-luna", openai_client=openai_client),
        model_timeout_seconds=4.0,
        run_timeout_seconds=5.0,
    )
    try:
        result = await SupportProgramRankingService(agent).rank(request)
    finally:
        await openai_client.close()

    assert len(captured_responses) == 1
    llm_output = json.loads(captured_responses[0]["output"][0]["content"][0]["text"])
    assert list(llm_output["rankings"]) == [candidate.id for candidate in request.candidates]
    for assessment in llm_output["rankings"].values():
        assert "programId" not in assessment
        assert "totalScore" not in assessment
        assert "targetAssessment" in assessment and "regionAssessment" in assessment
    assert result.original_query == request.original_query
    expected_ids = [request.candidates[-1].id] if has_relevant_candidate else []
    assert [ranking.program_id for ranking in result.rankings] == expected_ids
    assert [ranking.total_score for ranking in result.rankings] == ([100] if has_relevant_candidate else [])
