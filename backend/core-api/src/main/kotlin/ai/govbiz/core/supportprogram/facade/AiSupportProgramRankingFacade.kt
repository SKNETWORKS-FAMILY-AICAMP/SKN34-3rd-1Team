package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramRankingClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiScoredSupportProgramPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramCandidateRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramRankingPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramRankingRequest
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import org.springframework.stereotype.Component

/** 검색 Service에 LLM 점수화의 요청·검증·변환 과정을 단일 진입점으로 제공한다. */
@Component
class AiSupportProgramRankingFacade(
    private val client: AiSupportProgramRankingClient,
) : SupportProgramRankingFacade {
    override fun rank(
        query: String,
        candidates: List<CatalogSupportProgram>,
        limit: Int,
    ): List<SupportProgram> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        require(candidates.size <= SupportProgramRankingFacade.MAX_CANDIDATES) {
            "too many candidates"
        }
        require(limit in 1..SupportProgramRankingFacade.MAX_RESULTS) {
            "limit is outside the supported range"
        }

        val request = AiSupportProgramRankingRequest(
            originalQuery = query,
            scoringVersion = SCORING_VERSION,
            resultLimit = minOf(limit, candidates.size),
            candidates = java.util.List.copyOf(candidates.map(::toRequestCandidate)),
        )
        val payload = client.rankSupportPrograms(request)
        return validate(payload, query, candidates, request.resultLimit)
            ?: throw AiServiceCallException.invalidResponse(
                "AI Service support program rankings violated the internal contract",
                null,
            )
    }

    private fun validate(
        payload: AiSupportProgramRankingPayload,
        expectedQuery: String,
        candidates: List<CatalogSupportProgram>,
        expectedCount: Int,
    ): List<SupportProgram>? {
        if (payload.originalQuery != expectedQuery || payload.scoringVersion != SCORING_VERSION) {
            return null
        }
        val rankings = payload.rankings ?: return null
        if (rankings.size != expectedCount) return null

        val candidatesById = candidates.associateBy { it.program.id }
        val seenIds = HashSet<String>()
        var previousScore = MAX_TOTAL_SCORE + 1
        val programs = ArrayList<SupportProgram>(rankings.size)
        for (nullableRanking in rankings) {
            val ranking = nullableRanking ?: return null
            val programId = ranking.programId ?: return null
            val candidate = candidatesById[programId] ?: return null
            if (!seenIds.add(programId)) return null

            val score = validatedScore(ranking) ?: return null
            if (score > previousScore) return null
            previousScore = score
            val reasons = validatedReasons(ranking.recommendationReasons) ?: return null
            programs += candidate.program.copy(
                matchedReasons = reasons,
                recommendationScore = score,
            )
        }
        return java.util.List.copyOf(programs)
    }

    private fun validatedScore(ranking: AiScoredSupportProgramPayload): Int? {
        val semantic = ranking.semanticRelevance?.takeIf { it in 0..40 } ?: return null
        val target = ranking.targetFit?.takeIf { it in 0..25 } ?: return null
        val region = ranking.regionFit?.takeIf { it in 0..15 } ?: return null
        val status = ranking.applicationStatusFit?.takeIf { it in 0..10 } ?: return null
        val supportType = ranking.supportTypeFit?.takeIf { it in 0..10 } ?: return null
        val total = ranking.totalScore?.takeIf { it in 0..MAX_TOTAL_SCORE } ?: return null
        return total.takeIf { it == semantic + target + region + status + supportType }
    }

    private fun validatedReasons(values: List<String?>?): List<String>? {
        if (values == null || values.size !in 1..MAX_REASONS) return null
        val reasons = LinkedHashSet<String>()
        for (value in values) {
            val reason = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (reason.length > MAX_REASON_LENGTH ||
                reason.codePoints().anyMatch(Character::isISOControl)
            ) {
                return null
            }
            reasons += reason
        }
        return java.util.List.copyOf(reasons).takeIf { it.size == values.size }
    }

    private fun toRequestCandidate(candidate: CatalogSupportProgram): AiSupportProgramCandidateRequest {
        val program = candidate.program
        return AiSupportProgramCandidateRequest(
            id = program.id,
            title = program.title.take(MAX_TITLE_LENGTH),
            organization = program.organization.take(MAX_ORGANIZATION_LENGTH),
            summary = program.summary.take(MAX_SUMMARY_LENGTH),
            categories = limitedTerms(program.categories),
            regions = limitedTerms(program.regions),
            targetDescription = program.targetDescription.take(MAX_TARGET_LENGTH),
            applicationPeriod = program.applicationPeriod.take(MAX_PERIOD_LENGTH),
            status = program.status.name,
        )
    }

    private fun limitedTerms(values: List<String>): List<String> =
        java.util.List.copyOf(
            values.asSequence()
                .take(MAX_TERMS)
                .map { it.take(MAX_TERM_LENGTH) }
                .filter(String::isNotBlank)
                .toList(),
        )

    companion object {
        const val SCORING_VERSION = "govbiz-support-program-ranking-v1"
        private const val MAX_TOTAL_SCORE = 100
        private const val MAX_REASONS = 3
        private const val MAX_REASON_LENGTH = 120
        private const val MAX_TITLE_LENGTH = 300
        private const val MAX_ORGANIZATION_LENGTH = 200
        private const val MAX_SUMMARY_LENGTH = 1_000
        private const val MAX_TARGET_LENGTH = 500
        private const val MAX_PERIOD_LENGTH = 200
        private const val MAX_TERMS = 20
        private const val MAX_TERM_LENGTH = 100
    }
}
