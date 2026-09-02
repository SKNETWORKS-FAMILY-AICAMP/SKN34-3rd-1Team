package ai.govbiz.core.supportprogram.domain

import java.time.LocalDate

data class SupportProgram(
    val id: String,
    val title: String,
    val organization: String,
    val summary: String,
    val categories: List<String>,
    val regions: List<String>,
    val targetDescription: String,
    val applicationPeriod: String,
    val applicationStartDate: LocalDate?,
    val applicationEndDate: LocalDate?,
    val status: SupportProgramStatus,
    val sourceName: String,
    val sourceUrl: String,
    val matchedReasons: List<String>,
    val recommendationScore: Int? = null,
)
