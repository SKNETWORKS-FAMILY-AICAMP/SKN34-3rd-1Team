import pytest

from app.config import (
    DEFAULT_LLM_MODEL_TIMEOUT_SECONDS,
    DEFAULT_LLM_RUN_TIMEOUT_SECONDS,
    DEFAULT_OPENAI_MODEL,
    Settings,
    SettingsConfigurationError,
)


@pytest.fixture(autouse=True)
def configure_required_openai_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")


def test_reads_trimmed_openai_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", " private-key ")
    monkeypatch.setenv("OPENAI_MODEL", " test-model ")
    monkeypatch.setenv("LLM_MODEL_TIMEOUT_SECONDS", "1.25")
    monkeypatch.setenv("LLM_RUN_TIMEOUT_SECONDS", "1.75")

    settings = Settings.from_environment()

    assert settings.openai_api_key == "private-key"
    assert settings.openai_model == "test-model"
    assert settings.llm_model_timeout_seconds == 1.25
    assert settings.llm_run_timeout_seconds == 1.75


@pytest.mark.parametrize(
    ("environment_name", "field_name", "default"),
    [
        (
            "LLM_MODEL_TIMEOUT_SECONDS",
            "llm_model_timeout_seconds",
            DEFAULT_LLM_MODEL_TIMEOUT_SECONDS,
        ),
        (
            "LLM_RUN_TIMEOUT_SECONDS",
            "llm_run_timeout_seconds",
            DEFAULT_LLM_RUN_TIMEOUT_SECONDS,
        ),
    ],
)
@pytest.mark.parametrize("value", [None, "", "0", "-1", "invalid", "31"])
def test_uses_safe_timeout_default(
    monkeypatch: pytest.MonkeyPatch,
    environment_name: str,
    field_name: str,
    default: float,
    value: str | None,
) -> None:
    monkeypatch.delenv("LLM_TIMEOUT_SECONDS", raising=False)
    if value is None:
        monkeypatch.delenv(environment_name, raising=False)
    else:
        monkeypatch.setenv(environment_name, value)

    settings = Settings.from_environment()

    assert getattr(settings, field_name) == default


def test_uses_legacy_timeout_as_run_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("LLM_RUN_TIMEOUT_SECONDS", raising=False)
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "2.25")

    assert Settings.from_environment().llm_run_timeout_seconds == 2.25


@pytest.mark.parametrize("value", [None, "   "])
def test_requires_nonblank_openai_api_key(
    monkeypatch: pytest.MonkeyPatch,
    value: str | None,
) -> None:
    if value is None:
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    else:
        monkeypatch.setenv("OPENAI_API_KEY", value)

    with pytest.raises(SettingsConfigurationError, match="OPENAI_API_KEY is required"):
        Settings.from_environment()


def test_uses_default_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPENAI_MODEL", raising=False)

    assert Settings.from_environment().openai_model == DEFAULT_OPENAI_MODEL
