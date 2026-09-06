import hashlib
import importlib.util
import json
import shutil
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).with_name("verify-shared-run.py")
RUN = ROOT / "runs" / "support-program-catalog-20260906-v1"


class SharedRunVerificationTest(unittest.TestCase):
    def run_cli(self, path):
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--run-dir", str(path)],
            capture_output=True, text=True, check=False,
        )

    def test_real_frozen_run_replays_all_modes_offline(self):
        result = self.run_cli(RUN)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        value = json.loads(result.stdout)
        self.assertEqual(value["status"], "ok")
        self.assertEqual(value["judgments"], 1605)
        self.assertFalse(value["actualSearchEvaluated"])
        self.assertEqual(set(value["modes"]), {"ai-only", "hybrid", "human"})

    def test_missing_source_fails_without_running_or_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_cli(directory)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)["status"], "failed")
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_changed_judge_source_fails_cleanly(self):
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / RUN.name
            shutil.copytree(RUN, copy, ignore=shutil.ignore_patterns("outputs", "review.html", "web"))
            judge = copy / "review-v2" / "codex-ai-v1" / "judge-1.jsonl"
            judge.write_text(judge.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            result = self.run_cli(copy)
            self.assertNotEqual(result.returncode, 0)
            failure = json.loads(result.stdout)
            self.assertEqual(failure["status"], "failed")
            self.assertIn("Malformed judge JSON", failure["error"])

    def copied_run(self, directory):
        copy = Path(directory) / RUN.name
        relative_files = [
            "fixture-unlabeled.json", "query-set.json", "pool-config.json",
            "review-v2/review-pool.csv", "review-v2/review-pool-provenance.csv",
            "review-v2/review-pool-manifest.json", "review-v2/conversation-judgments.json",
        ]
        for mode in ("ai", "hybrid", "human"):
            relative_files.extend(f"review-v2/selected-{mode}-v1/{name}"
                                  for name in ("selection.json", "reviewed.csv", "review-progress.json"))
        relative_files.extend(
            f"review-v2/codex-ai-v1/{name}" for name in (
                "ai-review.json", "prepared.json", "policy.json", "assignments.json",
                "blind-input.jsonl", *[f"judge-{number}.jsonl" for number in range(1, 6)]))
        for relative in relative_files:
            target = copy / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(RUN / relative, target)
        return copy

    def test_config_and_provenance_tamper_fail_hash_validation(self):
        for relative in ("pool-config.json", "review-v2/review-pool-provenance.csv"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                copy = self.copied_run(directory)
                path = copy / relative
                path.write_bytes(path.read_bytes() + b"\n")
                result = self.run_cli(copy)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(json.loads(result.stdout)["status"], "failed")

    def test_missing_or_changed_selection_artifact_fails_cleanly(self):
        for relative, suffix in (
            ("review-v2/selected-ai-v1/selection.json", ""),
            ("review-v2/selected-hybrid-v1/reviewed.csv", "tamper"),
        ):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                copy = self.copied_run(directory)
                path = copy / relative
                if suffix:
                    path.write_bytes(path.read_bytes() + suffix.encode())
                else:
                    path.unlink()
                result = self.run_cli(copy)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(json.loads(result.stdout)["status"], "failed")

    def test_success_does_not_modify_frozen_sources(self):
        def digest_tree(root):
            return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in root.rglob("*") if path.is_file()}
        before = digest_tree(RUN)
        result = self.run_cli(RUN)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(before, digest_tree(RUN))

    def test_recollection_line_endings_do_not_change_saved_reference(self):
        spec = importlib.util.spec_from_file_location("shared_run_newline_test", SCRIPT)
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        original_collect = verifier.RUNNER.collect

        def collect_with_crlf(args):
            original_collect(args)
            output = Path(args.output)
            output.write_bytes(output.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))

        with patch.object(verifier.RUNNER, "collect", side_effect=collect_with_crlf), \
                patch("socket.socket.connect", side_effect=AssertionError("Network use is forbidden")):
            result = verifier.verify_run(RUN)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["judgments"], 1605)

    def test_duplicate_json_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            copy = self.copied_run(directory)
            path = copy / "review-v2" / "selected-ai-v1" / "selection.json"
            original = path.read_text(encoding="utf-8")
            changed = original.replace('"mode": "ai-only"', '"mode": "ai-only", "mode": "ai-only"', 1)
            self.assertNotEqual(original, changed)
            path.write_text(changed, encoding="utf-8")
            result = self.run_cli(copy)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)["status"], "failed")


if __name__ == "__main__":
    unittest.main()
