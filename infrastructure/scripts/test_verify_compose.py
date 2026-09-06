"""Exercise smoke-test cleanup guards with a fake Docker CLI; never touch Docker."""

from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("verify-compose.sh")
FAKE_DOCKER = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$VERIFY_DOCKER_CALLS"
case "$*" in
  *" config --quiet") exit "${VERIFY_FAKE_CONFIG_EXIT:-0}" ;;
  "ps "*|"network ls "*|"volume ls "*)
    if [[ "$*" == "${VERIFY_FAKE_EXISTING_KIND:-none} "* ]]; then
      printf '%s\\n' existing-resource
    fi
    exit "${VERIFY_FAKE_INSPECT_EXIT:-0}"
    ;;
  *" up --build --detach --remove-orphans") exit 42 ;;
esac
exit 0
"""


class VerifyComposeSafetyTest(unittest.TestCase):
    def run_script(self, **overrides):
        with tempfile.TemporaryDirectory(prefix="verify-compose-test-") as directory:
            root = Path(directory)
            docker = root / "docker"
            docker.write_text(FAKE_DOCKER, encoding="utf-8")
            docker.chmod(0o700)
            calls = root / "calls"
            environment = {
                "PATH": f"{root}:/usr/bin:/bin",
                "VERIFY_DOCKER_CALLS": str(calls),
                "VERIFY_COMPOSE_PROJECT_NAME": "govbiz-safety-test",
                **overrides,
            }
            result = subprocess.run(
                ["/bin/bash", str(SCRIPT)], env=environment,
                capture_output=True, text=True, timeout=10,
            )
            return result, calls.read_text(encoding="utf-8") if calls.exists() else ""

    def assert_no_stack_mutation(self, calls):
        self.assertNotIn(" up ", calls)
        self.assertNotIn(" down ", calls)
        self.assertNotIn(" logs ", calls)

    def test_existing_project_resources_are_never_reused_or_deleted(self):
        for kind in ("ps", "network ls", "volume ls"):
            with self.subTest(kind=kind):
                result, calls = self.run_script(VERIFY_FAKE_EXISTING_KIND=kind)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("already has", result.stderr)
                self.assert_no_stack_mutation(calls)

    def test_resource_inspection_failure_aborts_without_cleanup(self):
        result, calls = self.run_script(VERIFY_FAKE_INSPECT_EXIT="37")
        self.assertNotEqual(0, result.returncode)
        self.assert_no_stack_mutation(calls)

    def test_invalid_config_does_not_run_cleanup(self):
        result, calls = self.run_script(VERIFY_FAKE_CONFIG_EXIT="38")
        self.assertNotEqual(0, result.returncode)
        self.assert_no_stack_mutation(calls)

    def test_partially_started_new_project_is_cleaned_up(self):
        result, calls = self.run_script()
        self.assertEqual(42, result.returncode)
        self.assertIn(" up --build --detach --remove-orphans", calls)
        self.assertIn(" down --volumes --remove-orphans", calls)

    def test_keep_running_preserves_new_project_after_failure(self):
        result, calls = self.run_script(VERIFY_COMPOSE_KEEP_RUNNING="true")
        self.assertEqual(42, result.returncode)
        self.assertIn(" up --build --detach --remove-orphans", calls)
        self.assertNotIn(" down ", calls)


if __name__ == "__main__":
    unittest.main()
