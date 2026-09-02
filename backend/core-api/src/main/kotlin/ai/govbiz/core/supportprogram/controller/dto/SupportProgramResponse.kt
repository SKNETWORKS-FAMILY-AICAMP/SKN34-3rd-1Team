package ai.govbiz.core.supportprogram.controller.dto

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus

data class SupportProgramResponse(
    val id: String,
    val title: String,
    val organization: String,
    val summary: String,
    val categories: List<String>,
    val regions: List<String>,
    val targetDescription: String,
    val applicationPeriod: String,
    val applicationStartDate: String?,
    val applicationEndDate: String?,
    val status: SupportProgramStatus,
    val sourceName: String,
    val sourceUrl: String,
    val matchedReasons: List<String>,
    val recommendationScore: Int?,
) {
    companion object {
        fun from(program: SupportProgram): SupportProgramResponse =
            SupportProgramResponse(
                id = program.id,
                title = program.title,
                organization = program.organization,
                summary = program.summary,
                categories = program.categories,
                regions = program.regions,
                targetDescription = program.targetDescription,
                applicationPeriod = program.applicationPeriod,
                applicationStartDate = program.applicationStartDate?.toString(),
                applicationEndDate = program.applicationEndDate?.toString(),
                status = program.status,
                sourceName = program.sourceName,
                sourceUrl = program.sourceUrl,
                matchedReasons = program.matchedReasons,
                recommendationScore = program.recommendationScore,
            )
    }
}
