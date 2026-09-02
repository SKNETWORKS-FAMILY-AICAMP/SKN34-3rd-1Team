from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


SCORING_VERSION = "govbiz-support-program-ranking-v1"
MAX_CANDIDATES = 20


class SupportProgramStatus(StrEnum):
    OPEN = "OPEN"
    UPCOMING = "UPCOMING"
    CLOSED = "CLOSED"
    UNKNOWN = "UNKNOWN"


class SupportProgramCandidate(BaseModel):
    """Core가 공식 공고 원문에서 검증해 보낸 LLM 평가 후보."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    id: str = Field(min_length=1, max_length=200)
    title: str = Field(min_length=1, max_length=300)
    organization: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=1_000)
    categories: list[str] = Field(max_length=20)
    regions: list[str] = Field(max_length=20)
    target_description: str = Field(
        alias="targetDescription",
        min_length=1,
        max_length=500,
    )
    application_period: str = Field(
        alias="applicationPeriod",
        min_length=1,
        max_length=200,
    )
    status: SupportProgramStatus

    @field_validator(
        "id",
        "title",
        "organization",
        "summary",
        "target_description",
        "application_period",
        mode="before",
    )
    @classmethod
    def strip_text(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value

    @field_validator("categories", "regions")
    @classmethod
    def normalize_terms(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            term = value.strip()
            if not term or len(term) > 100:
                raise ValueError("candidate terms must contain 1 to 100 characters")
            key = term.casefold()
            if key not in seen:
                seen.add(key)
                normalized.append(term)
        return normalized


class SupportProgramRankingRequest(BaseModel):
    """Core가 LLM 평가를 요청할 때 사용하는 내부 계약."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    original_query: str = Field(
        alias="originalQuery",
        min_length=1,
        max_length=500,
    )
    scoring_version: Literal[SCORING_VERSION] = Field(alias="scoringVersion")
    result_limit: int = Field(alias="resultLimit", ge=1, le=5)
    candidates: list[SupportProgramCandidate] = Field(
        min_length=1,
        max_length=MAX_CANDIDATES,
    )

    @field_validator("original_query", mode="before")
    @classmethod
    def strip_query(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value

    @model_validator(mode="after")
    def require_unique_candidate_ids(self) -> "SupportProgramRankingRequest":
        ids = [candidate.id for candidate in self.candidates]
        if len(ids) != len(set(ids)):
            raise ValueError("candidate ids must be unique")
        return self


class ScoredSupportProgram(BaseModel):
    """LLM이 평가 기준별 점수와 근거를 반환하는 strict output 항목."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    program_id: str = Field(alias="programId", min_length=1, max_length=200)
    semantic_relevance: int = Field(alias="semanticRelevance", ge=0, le=40)
    target_fit: int = Field(alias="targetFit", ge=0, le=25)
    region_fit: int = Field(alias="regionFit", ge=0, le=15)
    application_status_fit: int = Field(alias="applicationStatusFit", ge=0, le=10)
    support_type_fit: int = Field(alias="supportTypeFit", ge=0, le=10)
    total_score: int = Field(alias="totalScore", ge=0, le=100)
    recommendation_reasons: list[str] = Field(
        alias="recommendationReasons",
        min_length=1,
        max_length=3,
    )

    @field_validator("program_id", mode="before")
    @classmethod
    def strip_program_id(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value

    @field_validator("recommendation_reasons")
    @classmethod
    def normalize_reasons(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            reason = value.strip()
            if not reason or len(reason) > 120:
                raise ValueError("recommendation reasons must contain 1 to 120 characters")
            if reason not in seen:
                seen.add(reason)
                normalized.append(reason)
        if not normalized:
            raise ValueError("at least one recommendation reason is required")
        return normalized

    @model_validator(mode="after")
    def require_exact_total(self) -> "ScoredSupportProgram":
        expected = (
            self.semantic_relevance
            + self.target_fit
            + self.region_fit
            + self.application_status_fit
            + self.support_type_fit
        )
        if self.total_score != expected:
            raise ValueError("totalScore must equal the sum of all score dimensions")
        return self


class SupportProgramRankingOutput(BaseModel):
    """Agent가 모든 입력 후보를 한 번씩 점수화한 structured output."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    rankings: list[ScoredSupportProgram] = Field(
        min_length=1,
        max_length=MAX_CANDIDATES,
    )

    @model_validator(mode="after")
    def require_unique_program_ids(self) -> "SupportProgramRankingOutput":
        ids = [ranking.program_id for ranking in self.rankings]
        if len(ids) != len(set(ids)):
            raise ValueError("ranked program ids must be unique")
        return self


class SupportProgramRankingResponse(BaseModel):
    """AI Service가 Core에 반환하는 검증·정렬된 상위 추천 계약."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    original_query: str = Field(alias="originalQuery")
    scoring_version: Literal[SCORING_VERSION] = Field(alias="scoringVersion")
    rankings: list[ScoredSupportProgram] = Field(min_length=1, max_length=5)
