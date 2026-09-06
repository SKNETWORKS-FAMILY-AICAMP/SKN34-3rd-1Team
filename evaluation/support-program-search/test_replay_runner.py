"""Offline replay-runner checks; execute-path tests use the AI Service venv and MockTransport."""

import asyncio
import copy
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from evaluate import CAPTURE_SCHEMA_VERSION, eligible_catalog_fingerprint, query_set_sha256


RUNNER_PATH = Path(__file__).with_name("replay-ranking.py")
SPEC = importlib.util.spec_from_file_location("replay_runner_under_test", RUNNER_PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
AI_DEPENDENCIES_AVAILABLE = all(
    importlib.util.find_spec(name) is not None for name in ("httpx", "fastapi", "agents", "qdrant_client")
)
SECRET_MARKER = "offline-test-key-do-not-log"


class ReplayRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.paths = {
            name: self.directory / f"{name}.json"
            for name in ("fixture", "requests", "source_capture", "export_metadata")
        }
        self.paths.update({name: self.directory / f"{name}.txt" for name in ("before_prompt", "after_prompt")})
        self.output = self.directory / "output"
        docs = [
            {"id": f"BIZINFO:TEST_{letter}", "text": f"테스트 공고 {letter}", "sortTimestamp": "2026-09-06T00:00:00"}
            for letter in "ABCDE"
        ]
        for doc in docs:
            doc["contentHash"] = hashlib.sha256(doc["text"].encode()).hexdigest()
        cases = [
            {"id": f"Q{number:02d}", "query": f"테스트 지원 질문 {number}",
             "split": "dev" if number <= 10 else "heldout", "relevantIds": []}
            for number in range(1, 17)
        ]
        catalog = {"presentProgramCount": len(docs), "eligibleProgramCount": len(docs),
                   "eligibleCatalogFingerprint": eligible_catalog_fingerprint(docs)}
        self.fixture = {
            "name": "synthetic-runner-test", "dataType": "synthetic_runner_test",
            "referenceDate": "2026-09-06", "docs": docs, "catalog": catalog, "cases": cases,
        }
        self.capture = {
            "schemaVersion": CAPTURE_SCHEMA_VERSION, "querySet": {
                "name": self.fixture["name"], "sha256": query_set_sha256(cases)},
            "capturedAt": "2026-09-06T01:00:00Z", "referenceDate": "2026-09-06",
            "acceptingOnly": True, "catalog": copy.deepcopy(catalog),
            "search": {"candidateLimit": 20, "finalResultLimit": 5,
                       "scoringVersion": "govbiz-support-program-ranking-v3"},
            "observations": [
                {**{key: case[key] for key in ("id", "query", "split")},
                 "candidateIds": [doc["id"] for doc in docs], "finalProgramIds": []}
                for case in cases
            ],
        }
        self.write_json(self.paths["fixture"], self.fixture)
        self.write_json(self.paths["source_capture"], self.capture)
        self.envelope = {
            "schemaVersion": "support-program-ranking-replay-input-v1", "referenceDate": "2026-09-06",
            "catalog": copy.deepcopy(catalog), "sourceCaptureSha256": runner.sha256_file(self.paths["source_capture"]),
            "queries": [
                {"id": case["id"], "split": case["split"], "request": {
                    "originalQuery": case["query"], "scoringVersion": "govbiz-support-program-ranking-v3", "resultLimit": 5,
                    "candidates": [
                        {"id": doc["id"], "title": doc["text"], "organization": "테스트 기관",
                         "summary": "오프라인 테스트 전용 내용", "categories": ["기술"], "regions": ["전국"],
                         "targetDescription": "중소기업", "applicationPeriod": "상시 접수", "status": "OPEN"}
                        for doc in docs
                    ],
                }} for case in cases
            ],
        }
        self.save_requests()
        self.paths["before_prompt"].write_text("before test prompt", encoding="utf-8")
        self.paths["after_prompt"].write_text("after test prompt", encoding="utf-8")

    @staticmethod
    def write_json(path, value):
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def save_requests(self):
        self.write_json(self.paths["requests"], self.envelope)
        self.write_json(self.paths["export_metadata"], {
            "sourceHashes": {"requestFileSha256": runner.sha256_file(self.paths["requests"])}
        })

    def cli(self):
        arguments = [sys.executable, "-B", str(RUNNER_PATH)]
        for name, path in self.paths.items():
            arguments.extend(["--" + name.replace("_", "-"), str(path)])
        arguments.extend(["--output-dir", str(self.output)])
        # An empty environment proves dry-run does not depend on credentials or inherited API configuration.
        return subprocess.run(arguments, env={"PYTHONDONTWRITEBYTECODE": "1"}, capture_output=True,
                              text=True, timeout=20, check=False)

    def test_dry_run_without_key_reports_32_calls_without_creating_output(self):
        result = self.cli()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"queryCount": 16, "maximumOpenaiCalls": 32, "embeddingCalls": 0, "execute": False},
                         json.loads(result.stdout))
        self.assertEqual("", result.stderr)
        self.assertFalse(self.output.exists())

    def test_cli_rejects_partial_query_set_without_creating_output(self):
        self.envelope["queries"].pop()
        self.save_requests()
        result = self.cli()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ValueError", result.stderr)
        self.assertEqual("", result.stdout)
        self.assertFalse(self.output.exists())

    def test_cli_rejects_reordered_queries_without_creating_output(self):
        self.envelope["queries"].reverse()
        self.save_requests()
        result = self.cli()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.output.exists())

    async def execute_offline(self, *, upstream_status=200, fail_close=False):
        import httpx
        from fastapi import FastAPI
        from fastapi.responses import JSONResponse
        from app.support_program_ranking.prompt import SUPPORT_PROGRAM_RANKING_INSTRUCTIONS

        attempts, received_requests, closed = [], [], []

        def upstream(request):
            attempts.append(request)
            if upstream_status != 200:
                return httpx.Response(upstream_status, text=f"non-JSON upstream failure: {SECRET_MARKER}")
            return httpx.Response(200, json={
                "status": "completed", "model": "gpt-5.6-luna", "usage": {
                    "input_tokens": 11, "output_tokens": 7, "input_tokens_details": {"cached_tokens": 0},
                },
            })

        def create_offline_app(*, settings):
            app = FastAPI()
            # Both transports are local: this MockTransport and runner's ASGITransport.
            # No OpenAI client, Qdrant client, socket or database is created.
            upstream_client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))

            async def close():
                await upstream_client.aclose()
                closed.append(upstream_client)
                if fail_close:
                    raise RuntimeError(SECRET_MARKER)

            app.state.container = SimpleNamespace(
                support_program_ranking_service=SimpleNamespace(_agent=SimpleNamespace(
                    _agent=SimpleNamespace(clone=lambda **kwargs: SimpleNamespace(**kwargs)))),
                openai_client=SimpleNamespace(_client=upstream_client), close=close,
            )

            @app.post("/internal/v1/support-program-rankings/rank")
            async def rank(payload: dict):
                received_requests.append(copy.deepcopy(payload))
                response = await upstream_client.post("https://api.openai.com/v1/responses", json=payload)
                if response.status_code != 200:
                    return JSONResponse(status_code=503, content={"detail": "offline upstream unavailable"})
                return {"originalQuery": payload["originalQuery"], "scoringVersion": payload["scoringVersion"],
                        "rankings": []}

            return app

        args = SimpleNamespace(**self.paths, output_dir=self.output)
        prompts = {"before": "before test prompt", "after": SUPPORT_PROGRAM_RANKING_INSTRUCTIONS}
        self.execution_state = (attempts, received_requests, closed)
        with patch.dict(os.environ, {"OPENAI_API_KEY": SECRET_MARKER}, clear=True), \
                patch("app.main.create_app", side_effect=create_offline_app), redirect_stdout(io.StringIO()):
            await runner.execute(args, self.envelope, prompts)

    def read_usage(self):
        return [json.loads(line) for line in (self.output / "api-usage.jsonl").read_text().splitlines()]

    def read_manifest(self):
        return json.loads((self.output / "execution-manifest.json").read_text())

    def assert_no_secret_in_artifacts(self):
        for path in self.output.iterdir():
            self.assertNotIn(SECRET_MARKER, path.read_text(), str(path))

    @unittest.skipUnless(AI_DEPENDENCIES_AVAILABLE, "Execute-path tests require the AI Service venv")
    def test_non_json_upstream_failure_preserves_http_status_and_failed_manifest(self):
        with self.assertRaisesRegex(RuntimeError, "Ranking HTTP status 503"):
            asyncio.run(self.execute_offline(upstream_status=502))
        usage, manifest = self.read_usage(), self.read_manifest()
        self.assertEqual(["request", "response", "failed"], [entry["event"] for entry in usage])
        self.assertEqual(502, usage[1]["status"])
        self.assertEqual(1, usage[1]["sequence"])
        self.assertGreaterEqual(usage[1]["elapsedSeconds"], 0)
        self.assertIsNone(usage[1]["usage"])
        self.assertEqual("failed", manifest["status"])
        self.assertEqual(1, manifest["actualCalls"])
        self.assertEqual(0, manifest["completedRankings"])
        self.assertEqual(1, manifest["variants"]["before"]["httpFailures"])
        self.assertFalse(manifest["variants"]["before"]["usageComplete"])
        self.assertIsNone(manifest["variants"]["before"]["inputTokens"])
        self.assertIsNone(manifest["variants"]["before"]["outputTokens"])
        self.assertEqual(1, len(self.execution_state[0]))
        self.assertEqual(2, len(self.execution_state[2]))
        self.assert_no_secret_in_artifacts()

    @unittest.skipUnless(AI_DEPENDENCIES_AVAILABLE, "Execute-path tests require the AI Service venv")
    def test_close_failures_preserve_manifest_and_all_identical_request_pairs(self):
        with self.assertRaisesRegex(RuntimeError, "Replay cleanup failed"):
            asyncio.run(self.execute_offline(fail_close=True))
        manifest, usage = self.read_manifest(), self.read_usage()
        self.assertEqual("failed", manifest["status"])
        self.assertEqual(["RuntimeError", "RuntimeError"], manifest["cleanupErrors"])
        self.assertEqual("cleanup_failed", usage[-1]["event"])
        self.assertEqual(32, manifest["plannedCalls"])
        self.assertEqual(32, manifest["actualCalls"])
        self.assertEqual(32, manifest["completedRankings"])
        attempts, received, closed = self.execution_state
        self.assertEqual(32, len(attempts))
        self.assertEqual(2, len(closed))
        for index, query in enumerate(self.envelope["queries"]):
            self.assertEqual(query["request"], received[2 * index])
            self.assertEqual(received[2 * index], received[2 * index + 1])
        results = [json.loads(line) for line in (self.output / "results.jsonl").read_text().splitlines()]
        for index in range(0, len(results), 2):
            self.assertEqual({"before", "after"}, {row["variant"] for row in results[index:index + 2]})
            self.assertEqual(results[index]["requestSha256"], results[index + 1]["requestSha256"])
            self.assertEqual(results[index]["modelInputSha256"], results[index + 1]["modelInputSha256"])
        self.assertEqual(runner.sha256_file(self.output / "results.jsonl"), manifest["resultsSha256"])
        self.assertEqual(runner.sha256_file(self.output / "api-usage.jsonl"), manifest["apiUsageSha256"])
        self.assert_no_secret_in_artifacts()


if __name__ == "__main__":
    unittest.main()
