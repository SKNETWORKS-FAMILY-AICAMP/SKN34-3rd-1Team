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
    qdrant_url: str = "http://localhost:6333"
    qdrant_api_key: str | None = None
    qdrant_timeout_seconds: float = 5.0
    openai_embedding_model: str = "text-embedding-3-small"
    openai_embedding_dimensions: int = 1536
    embedding_timeout_seconds: float = 15.0

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
            qdrant_url=_optional_value(environ.get("QDRANT_URL")) or "http://localhost:6333",
            qdrant_api_key=_optional_value(environ.get("QDRANT_API_KEY")),
            qdrant_timeout_seconds=_positive_float(
                environ.get("QDRANT_TIMEOUT_SECONDS"), default=5.0,
            ),
            openai_embedding_model=_embedding_model(),
            openai_embedding_dimensions=_embedding_dimensions(),
            embedding_timeout_seconds=_positive_float(
                environ.get("EMBEDDING_TIMEOUT_SECONDS"), default=15.0,
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


def _embedding_model() -> str:
    model = _optional_value(environ.get("OPENAI_EMBEDDING_MODEL")) or "text-embedding-3-small"
    if model not in {"text-embedding-3-small", "text-embedding-3-large"}:
        raise SettingsConfigurationError("OPENAI_EMBEDDING_MODEL is not supported")
    return model


def _embedding_dimensions() -> int:
    maximum = 1536 if _embedding_model() == "text-embedding-3-small" else 3072
    try:
        dimensions = int(environ.get("OPENAI_EMBEDDING_DIMENSIONS", "1536"))
    except ValueError as error:
        raise SettingsConfigurationError("OPENAI_EMBEDDING_DIMENSIONS is invalid") from error
    if not 1 <= dimensions <= maximum:
        raise SettingsConfigurationError("OPENAI_EMBEDDING_DIMENSIONS is invalid")
    return dimensions
