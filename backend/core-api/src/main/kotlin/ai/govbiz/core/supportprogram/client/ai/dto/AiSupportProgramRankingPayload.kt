package ai.govbiz.core.supportprogram.client.ai.dto

/** nullable 필드로 누락과 유효한 0점을 구분하는 내부 AI 응답 계약. */
data class AiSupportProgramRankingPayload(
    val originalQuery: String?,
    val scoringVersion: String?,
    val rankings: List<AiScoredSupportProgramPayload?>?,
)

enum class AiSupportProgramEligibility {
    MATCH,
    INCOMPATIBLE,
    UNKNOWN,
}

data class AiScoredSupportProgramPayload(
    val programId: String?,
    val semanticRelevance: Int?,
    val targetFit: Int?,
    val targetEligibility: AiSupportProgramEligibility?,
    val regionFit: Int?,
    val regionEligibility: AiSupportProgramEligibility?,
    val applicationStatusFit: Int?,
    val supportTypeFit: Int?,
    val totalScore: Int?,
    val recommendationReasons: List<String?>?,
)
