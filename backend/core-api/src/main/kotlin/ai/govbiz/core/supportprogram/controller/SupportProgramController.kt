package ai.govbiz.core.supportprogram.controller

import ai.govbiz.core.supportprogram.controller.dto.SupportProgramSearchResponse
import ai.govbiz.core.supportprogram.service.search.SupportProgramSearchService
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/support-programs")
class SupportProgramController(
    private val searchService: SupportProgramSearchService,
) {

    @GetMapping("/search")
    fun search(
        @RequestParam @Size(max = 500) query: String,
        @RequestParam(defaultValue = "true") acceptingOnly: Boolean,
    ): SupportProgramSearchResponse =
        SupportProgramSearchResponse.from(searchService.search(query, acceptingOnly))
}
