"""평가 도구 자체의 테스트. 네트워크와 실제 모델을 사용하지 않는다."""

import asyncio
from copy import deepcopy
import importlib.util
import json
from pathlib import Path

import httpx2
import pytest


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("evidence_evaluate", HERE / "evaluate.py")
evaluate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluate)


@pytest.fixture
def loaded():
    return evaluate.load_fixture(HERE / "fixture.json")


def capture_for(loaded):
    _, prepared, fixture_hash = loaded
    return {
        "schemaVersion": "support-program-evidence-capture-v1",
        "fixtureSha256": fixture_hash,
        "promptSha256": "a" * 64,
        "runnerSha256": "b" * 64,
        "model": "recorded-model-not-current-default",
        "modelTimeoutSeconds": 25, "runTimeoutSeconds": 30,
        "completed": True,
        "cases": [{
            "caseId": case["id"], "requestSha256": evaluate.request_digest(request),
            "outcome": "success", "response": {
                "answer": "테스트용 고정 응답으로 실제 모델 출력이 아닙니다.",
                "answerStatus": case["expectedStatus"],
                "citationChunkIds": [request.chunks[order].id for order in case["expectedCitationOrders"]],
            },
        } for case, request in prepared],
    }


def test_default_reports_no_measurement(loaded):
    result = evaluate.report(*loaded)
    assert result["documentCount"] == 3
    assert result["caseCount"] == result["maxApiCallsOnExecute"] == 12
    assert not result["measured"] and not result["completed"]
    assert result["referenceSource"] == "ai-authored"
    assert result["statusAccuracy"] is result["semanticFaithfulness"] is None


def test_correct_citation_ids_do_not_prove_true_answer(loaded):
    capture = capture_for(loaded)
    capture["cases"][0]["response"]["answer"] = "서울 밖의 개인사업자도 신청 가능합니다."
    result = evaluate.report(*loaded, capture)
    assert result["statusAccuracy"] == result["referenceCitationRecall"] == 1
    assert result["semanticFaithfulness"] is None
    assert result["semanticReviewRequired"]
    assert result["execution"]["model"] == "recorded-model-not-current-default"


@pytest.mark.parametrize("mutation", [
    lambda f: f.update(dataType="real"),
    lambda f: f.update(referenceSource="human"),
    lambda f: f["cases"].append(deepcopy(f["cases"][0])),
    lambda f: f["cases"][1].update(id="E01"),
    lambda f: f["cases"][0].update(documentId=[]),
    lambda f: f["cases"][0].update(question=" "),
    lambda f: f["cases"][0].update(expectedStatus=[]),
    lambda f: f["cases"][0].update(expectedCitationOrders=[]),
    lambda f: f["cases"][0].update(expectedCitationOrders=[99]),
    lambda f: f["cases"][0].update(expectedCitationOrders=[0, 0]),
    lambda f: f["cases"][0].update(forbiddenClaims=[]),
    lambda f: f["documents"][0]["chunks"][0].update(order=True),
    lambda f: f["documents"][0]["chunks"][0].update(text="\x00"),
    lambda f: f["documents"][0].update(id="not-canonical"),
])
def test_rejects_invalid_fixture(loaded, tmp_path, mutation):
    fixture = deepcopy(loaded[0])
    mutation(fixture)
    path = tmp_path / "invalid.json"
    path.write_text(json.dumps(fixture))
    with pytest.raises(ValueError):
        evaluate.load_fixture(path)


@pytest.mark.parametrize("mutation", [
    lambda c: c.update(fixtureSha256="0" * 64),
    lambda c: c.pop("model"),
    lambda c: c.update(promptSha256="invalid"),
    lambda c: c.update(runTimeoutSeconds=float("nan")),
    lambda c: c["cases"][0].update(caseId="E02"),
    lambda c: c["cases"][0].update(requestSha256="0" * 64),
    lambda c: c["cases"][0]["response"].update(citationChunkIds=["0" * 64]),
    lambda c: c["cases"][0]["response"].update(answerStatus="INSUFFICIENT_EVIDENCE"),
    lambda c: c["cases"].pop(),
    lambda c: c["cases"][0].update(outcome="error"),
    lambda c: c.update(completed=False),
])
def test_rejects_invalid_capture(loaded, mutation):
    capture = capture_for(loaded)
    mutation(capture)
    with pytest.raises(ValueError):
        evaluate.report(*loaded, capture)


def test_incomplete_run_does_not_publish_partial_accuracy(loaded):
    capture = capture_for(loaded)
    capture["completed"] = False
    capture["cases"] = capture["cases"][:2]
    capture["cases"][-1].update(outcome="error")
    result = evaluate.report(*loaded, capture)
    assert result["observedCaseCount"] == 2
    assert result["measured"] and not result["completed"]
    assert result["statusAccuracy"] is result["referenceCitationRecall"] is None


def test_single_case_capture_is_reproducible_without_claiming_full_coverage(loaded):
    capture = capture_for(loaded)
    capture["caseIds"] = ["E01"]
    capture["cases"] = capture["cases"][:1]
    result = evaluate.report(*loaded, capture)
    assert result["caseCount"] == 1 and result["fixtureCaseCount"] == 12
    assert result["selectedCaseIds"] == ["E01"]
    assert result["completed"]
    with pytest.raises(ValueError):
        evaluate.select_cases(loaded[1], ["not-a-case"])


def test_cli_default_never_executes_or_writes(loaded, monkeypatch, capsys):
    def forbidden(*args):
        pytest.fail("default mode must not execute")
    monkeypatch.setattr(evaluate, "execute", forbidden)
    monkeypatch.setattr(evaluate.sys, "argv", ["evaluate.py"])
    assert evaluate.main() == 0
    assert not json.loads(capsys.readouterr().out)["measured"]


@pytest.mark.parametrize("status", [200, 429, "invalid-citation"])
def test_execute_uses_production_agent_with_mock_http_only(loaded, tmp_path, monkeypatch, status):
    requests = []
    fake_capture = capture_for(loaded)
    real_client = httpx2.AsyncClient

    def handler(request):
        assert str(request.url) == "https://api.openai.com/v1/responses"
        body = json.loads(request.content)
        assert body["store"] is False
        assert body["model"] == evaluate.DEFAULT_OPENAI_MODEL
        assert body["max_output_tokens"] == 2000
        assert body["tools"] == []
        output = fake_capture["cases"][len(requests)]["response"]
        requests.append(body)
        if status == 429:
            return httpx2.Response(status, json={"error": {"message": "SECRET-MUST-NOT-PERSIST", "type": "rate_limit_error"}})
        if status == "invalid-citation":
            output["citationChunkIds"] = ["0" * 64]
        return httpx2.Response(200, json={
            "id": "resp_test", "created_at": 0, "object": "response",
            "model": evaluate.DEFAULT_OPENAI_MODEL, "status": "completed",
            "error": None, "incomplete_details": None,
            "output": [{"id": "msg_test", "type": "message", "role": "assistant", "status": "completed",
                        "content": [{"type": "output_text", "annotations": [], "text": json.dumps(output)}]}],
            "parallel_tool_calls": False, "tool_choice": "none", "tools": [],
            "usage": {"input_tokens": 100, "output_tokens": 50, "total_tokens": 150},
        })

    class MockClient(real_client):
        def __init__(self, **kwargs):
            super().__init__(transport=httpx2.MockTransport(handler), **kwargs)

    monkeypatch.setattr(httpx2, "AsyncClient", MockClient)
    monkeypatch.setenv("OPENAI_API_KEY", "fake-key-never-sent")
    output = tmp_path / "new-run"
    capture = asyncio.run(evaluate.execute(loaded[1], loaded[2], output))
    assert len(requests) == (12 if status == 200 else 1)
    assert capture["completed"] == (status == 200)
    assert len(capture["apiResponses"]) == len(requests)
    assert capture["apiResponses"][0]["usage"] == (
        {"input_tokens": 100, "output_tokens": 50, "total_tokens": 150} if status != 429 else None
    )
    if status == "invalid-citation":
        assert "0" * 64 in capture["apiResponses"][0]["outputTexts"][0]
    saved = (output / "capture.json").read_text()
    assert "SECRET-MUST-NOT-PERSIST" not in saved and "fake-key" not in saved
    assert evaluate.report(*loaded, capture)["completed"] == (status == 200)


def test_execute_requires_explicit_key_and_new_directory(loaded, tmp_path, monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    with pytest.raises(ValueError):
        asyncio.run(evaluate.execute(loaded[1], loaded[2], tmp_path / "absent"))
    assert not (tmp_path / "absent").exists()
    monkeypatch.setenv("OPENAI_API_KEY", "not-used")
    with pytest.raises(FileExistsError):
        asyncio.run(evaluate.execute(loaded[1], loaded[2], tmp_path))


@pytest.mark.parametrize("capture_path", sorted((HERE / "runs").glob("*/capture.json")))
def test_shared_run_reports_recalculate_without_api(loaded, capture_path):
    actual = evaluate.report(*loaded, json.loads(capture_path.read_text()))
    expected = json.loads((capture_path.parent / "report.json").read_text())
    assert actual == expected
