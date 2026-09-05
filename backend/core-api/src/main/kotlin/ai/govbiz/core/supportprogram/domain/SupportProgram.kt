package ai.govbiz.core.supportprogram.domain

import java.time.LocalDate

data class SupportProgram(
    val id: String,
    val sourceCode: String,
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
) {
    init {
        require(SOURCE_CODE_PATTERN.matches(sourceCode)) {
            "sourceCode must be an uppercase provider code without a colon"
        }
    }

    /** 서로 다른 제공처의 같은 원본 ID를 구분하는 내부 검색·AI 식별자입니다. */
    val sourceQualifiedId: String
        get() = "$sourceCode:$id"

    private companion object {
        val SOURCE_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}
