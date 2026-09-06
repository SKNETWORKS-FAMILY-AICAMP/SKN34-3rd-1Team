import asyncio

from agents import (
    Agent,
    MaxTurnsExceeded,
    Model,
    ModelBehaviorError,
    ModelRefusalError,
    ModelSettings,
    ModelTimeoutError,
    RunConfig,
    Runner,
)
from openai import OpenAIError
from openai.types.shared import Reasoning
from pydantic import ConfigDict, ValidationError, create_model

from app.support_program_ranking.errors import AgentExecutionError

from .models import (
    AssessedSupportProgram,
    SupportProgramAssessment,
    SupportProgramRankingOutput,
    SupportProgramRankingRequest,
)
from .prompt import SUPPORT_PROGRAM_RANKING_INSTRUCTIONS


class SupportProgramRecommendationAgent:
    """한 번의 structured LLM 호출로 모든 공고 후보를 점수화한다."""

    def __init__(
        self,
        *,
        model: Model,
        model_timeout_seconds: float,
        run_timeout_seconds: float,
    ) -> None:
        self._run_timeout_seconds = run_timeout_seconds
        self._agent: Agent[None] = Agent(
            name="GovBiz Support Program Recommendation Scorer",
            instructions=SUPPORT_PROGRAM_RANKING_INSTRUCTIONS,
            model=model,
            model_settings=ModelSettings(
                max_tokens=4_000,
                reasoning=Reasoning(effort="none"),
                store=False,
                timeout=model_timeout_seconds,
            ),
        )
        self._run_config = RunConfig(
            workflow_name="GovBiz support program recommendation ranking",
            tracing_disabled=True,
            trace_include_sensitive_data=False,
        )

    async def rank(
        self,
        request: SupportProgramRankingRequest,
    ) -> SupportProgramRankingOutput:
        candidate_count = len(request.candidates)
        rankings_type = create_model(
            f"SupportProgramAssessmentsFor{candidate_count}Candidates",
            __config__=ConfigDict(extra="forbid", frozen=True),
            **{
                candidate.id: (SupportProgramAssessment, ...)
                for candidate in request.candidates
            },
        )
        output_type = create_model(
            f"SupportProgramRankingOutputFor{candidate_count}Candidates",
            __config__=ConfigDict(extra="forbid", frozen=True),
            rankings=(rankings_type, ...),
        )
        # 모든 후보 ID를 필수 속성 키로 고정해 배열의 ID 누락·중복·추가 생성을 막는다.
        agent = self._agent.clone(output_type=output_type)
        try:
            async with asyncio.timeout(self._run_timeout_seconds):
                result = await Runner.run(
                    agent,
                    request.model_dump_json(by_alias=True),
                    max_turns=1,
                    run_config=self._run_config,
                )
        except (
            MaxTurnsExceeded,
            ModelBehaviorError,
            ModelRefusalError,
            ModelTimeoutError,
            OpenAIError,
            TimeoutError,
            ValidationError,
        ) as error:
            raise AgentExecutionError(
                "Support program recommendation agent did not produce a usable result"
            ) from error

        output = result.final_output
        if not isinstance(output, output_type):
            raise AgentExecutionError(
                "Support program recommendation agent returned an unexpected output type"
            )
        return SupportProgramRankingOutput(
            rankings=[
                AssessedSupportProgram(
                    program_id=candidate.id,
                    **getattr(output.rankings, candidate.id).model_dump(),
                )
                for candidate in request.candidates
            ]
        )
