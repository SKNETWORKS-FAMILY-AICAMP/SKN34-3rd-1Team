package ai.govbiz.core.supportprogram.controller.dto

import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchResult

data class SupportProgramSearchResponse(
    val query: String,
    val programs: List<SupportProgramResponse>,
) {
    companion object {
        fun from(result: SupportProgramSearchResult): SupportProgramSearchResponse =
            SupportProgramSearchResponse(
                query = result.query,
                programs = java.util.List.copyOf(
                    result.programs.map(SupportProgramResponse::from),
                ),
            )
    }
}
