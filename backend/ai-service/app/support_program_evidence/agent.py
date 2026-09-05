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
from pydantic import ValidationError

from app.support_program_evidence.errors import SupportProgramEvidenceError
from app.support_program_evidence.models import (
    SupportProgramEvidenceAnswerOutput,
    SupportProgramEvidenceAnswerRequest,
)
from app.support_program_evidence.prompt import (
    SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS,
)


class SupportProgramEvidenceAnswerAgent:
    """한 번의 structured LLM 호출로 상세 공고 근거 답변을 생성한다."""

    def __init__(
        self,
        *,
        model: Model,
        model_timeout_seconds: float,
        run_timeout_seconds: float,
    ) -> None:
        self._run_timeout_seconds = run_timeout_seconds
        self._agent: Agent[None] = Agent(
            name="GovBiz Support Program Evidence Answerer",
            instructions=SUPPORT_PROGRAM_EVIDENCE_ANSWER_INSTRUCTIONS,
            model=model,
            output_type=SupportProgramEvidenceAnswerOutput,
            model_settings=ModelSettings(
                max_tokens=2_000,
                reasoning=Reasoning(effort="none"),
                store=False,
                timeout=model_timeout_seconds,
            ),
        )
        self._run_config = RunConfig(
            workflow_name="GovBiz support program evidence answer",
            tracing_disabled=True,
            trace_include_sensitive_data=False,
        )

    async def answer(
        self,
        request: SupportProgramEvidenceAnswerRequest,
    ) -> SupportProgramEvidenceAnswerOutput:
        try:
            async with asyncio.timeout(self._run_timeout_seconds):
                result = await Runner.run(
                    self._agent,
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
            raise SupportProgramEvidenceError() from error

        output = result.final_output
        if not isinstance(output, SupportProgramEvidenceAnswerOutput):
            raise SupportProgramEvidenceError()
        return output
