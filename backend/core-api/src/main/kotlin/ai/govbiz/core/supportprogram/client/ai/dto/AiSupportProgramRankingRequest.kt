package ai.govbiz.core.supportprogram.client.ai.dto

data class AiSupportProgramCandidateRequest(
    val id: String,
    val title: String,
    val organization: String,
    val summary: String,
    val categories: List<String>,
    val regions: List<String>,
    val targetDescription: String,
    val applicationPeriod: String,
    val status: String,
)

data class AiSupportProgramRankingRequest(
    val originalQuery: String,
    val scoringVersion: String,
    val resultLimit: Int,
    val candidates: List<AiSupportProgramCandidateRequest>,
)
