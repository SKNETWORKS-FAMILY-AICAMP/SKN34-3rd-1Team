from dataclasses import dataclass
from os import environ


DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
DEFAULT_LLM_MODEL_TIMEOUT_SECONDS = 8.0
DEFAULT_LLM_RUN_TIMEOUT_SECONDS = 10.0


@dataclass(frozen=True, slots=True)
class Settings:
    """환경변수에서 읽는 지원사업 추천 점수화 agent 설정."""

    openai_api_key: str
    openai_model: str
    llm_model_timeout_seconds: float
    llm_run_timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "Settings":
        legacy_run_timeout = environ.get("LLM_TIMEOUT_SECONDS")
        openai_api_key = _optional_value(environ.get("OPENAI_API_KEY"))
        if openai_api_key is None:
            raise SettingsConfigurationError("OPENAI_API_KEY is required")

        return cls(
            openai_api_key=openai_api_key,
            openai_model=(
                environ.get("OPENAI_MODEL", DEFAULT_OPENAI_MODEL).strip()
                or DEFAULT_OPENAI_MODEL
            ),
            llm_model_timeout_seconds=_positive_float(
                environ.get("LLM_MODEL_TIMEOUT_SECONDS"),
                default=DEFAULT_LLM_MODEL_TIMEOUT_SECONDS,
            ),
            llm_run_timeout_seconds=_positive_float(
                environ.get("LLM_RUN_TIMEOUT_SECONDS", legacy_run_timeout),
                default=DEFAULT_LLM_RUN_TIMEOUT_SECONDS,
            ),
        )


class SettingsConfigurationError(RuntimeError):
    """필수 AI Service 환경설정이 없을 때 발생하는 시작 오류."""


def _optional_value(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    return normalized or None


def _positive_float(value: str | None, *, default: float) -> float:
    if value is None:
        return default
    try:
        parsed = float(value)
    except ValueError:
        return default
    return parsed if 0 < parsed <= 30 else default
