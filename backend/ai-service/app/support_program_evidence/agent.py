import asyncio
import json

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
    SupportProgramEvidenceAnswerSelection,
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
            output_type=SupportProgramEvidenceAnswerSelection,
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
        payload = {
            "question": request.question,
            "chunks": [
                {"index": index, **chunk.model_dump(by_alias=True, exclude={"id"})}
                for index, chunk in enumerate(request.chunks)
            ],
        }
        try:
            async with asyncio.timeout(self._run_timeout_seconds):
                result = await Runner.run(
                    self._agent,
                    json.dumps(payload, ensure_ascii=False),
                    max_turns=1,
                    run_config=self._run_config,
                )
            output = result.final_output
            if not isinstance(output, SupportProgramEvidenceAnswerSelection):
                raise SupportProgramEvidenceError()
            selection = SupportProgramEvidenceAnswerSelection.model_validate(output.model_dump(by_alias=True))
            if any(index >= len(request.chunks) for index in selection.citation_chunk_indexes):
                raise SupportProgramEvidenceError()
            return SupportProgramEvidenceAnswerOutput(
                answer=selection.answer,
                answerStatus=selection.answer_status,
                citationChunkIds=[request.chunks[index].id for index in selection.citation_chunk_indexes],
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
