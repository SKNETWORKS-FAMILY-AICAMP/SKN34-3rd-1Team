package ai.govbiz.core.supportprogram.controller

import ai.govbiz.core.supportprogram.controller.dto.SupportProgramSearchResponse
import ai.govbiz.core.supportprogram.controller.dto.SupportProgramResponse
import ai.govbiz.core.supportprogram.controller.dto.SupportProgramEvidenceAnswerResponse
import ai.govbiz.core.supportprogram.controller.dto.SupportProgramEvidenceQuestionRequest
import ai.govbiz.core.supportprogram.controller.validation.CodePointMax
import ai.govbiz.core.supportprogram.service.detail.SupportProgramDetailService
import ai.govbiz.core.supportprogram.service.evidence.SupportProgramEvidenceService
import ai.govbiz.core.supportprogram.service.search.SupportProgramSearchService
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/support-programs")
class SupportProgramController(
    private val searchService: SupportProgramSearchService,
    private val detailService: SupportProgramDetailService,
    private val evidenceService: SupportProgramEvidenceService,
) {

    @GetMapping("/search")
    fun search(
        @RequestParam @Size(max = 500) query: String,
        @RequestParam(defaultValue = "true") acceptingOnly: Boolean,
    ): SupportProgramSearchResponse =
        SupportProgramSearchResponse.from(searchService.search(query, acceptingOnly))

    @GetMapping("/detail")
    fun detail(
        @RequestParam
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}")
        sourceCode: String,
        @RequestParam
        @NotBlank
        @CodePointMax(max = 255)
        @Pattern(regexp = "(?Us)^(?!\\s)(?!.*\\s$)(?!.*\\p{C}).+$")
        sourceProgramId: String,
    ): SupportProgramResponse =
        SupportProgramResponse.from(detailService.get(sourceCode, sourceProgramId))

    @PostMapping("/detail/answers")
    fun answerFromOfficialSource(
        @RequestBody @jakarta.validation.Valid request: SupportProgramEvidenceQuestionRequest,
    ): SupportProgramEvidenceAnswerResponse =
        SupportProgramEvidenceAnswerResponse.from(
            evidenceService.answer(
                sourceCode = request.sourceCode,
                sourceProgramId = request.sourceProgramId,
                question = request.question,
            ),
        )
}
