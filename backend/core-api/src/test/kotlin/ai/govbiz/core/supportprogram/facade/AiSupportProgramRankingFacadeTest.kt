package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.AiServiceFailure
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramRankingClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEligibility
import ai.govbiz.core.supportprogram.client.ai.dto.AiScoredSupportProgramPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramRankingPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramRankingRequest
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AiSupportProgramRankingFacadeTest {

    private val client = StubRankingClient()

    @Test
    fun sendsTheVersionedScoringContractAndMapsValidatedRankings() {
        val candidates = candidates()
        client.response = response(
            score("second", semantic = 40, total = 85, reason = "질의와 직접 관련"),
            score("first", semantic = 20, total = 65, reason = "일부 관련"),
        )

        val programs = facade().rank(QUERY, candidates, 5)

        val request = client.requests.single()
        assertEquals(AiSupportProgramRankingFacade.SCORING_VERSION, request.scoringVersion)
        assertEquals(2, request.resultLimit)
        assertEquals(listOf("first", "second"), request.candidates.map { it.id })
        assertEquals(listOf("second", "first"), programs.map { it.id })
        assertEquals(85, programs.first().recommendationScore)
        assertEquals(listOf("질의와 직접 관련"), programs.first().matchedReasons)
    }

    @Test
    fun acceptsFewerRankingsWhenOnlySomeCandidatesMeetTheRecommendationMinimum() {
        client.reset(response(score("second", semantic = 40, total = 85, reason = "질의와 직접 관련")))

        val programs = facade().rank(QUERY, candidates(), 5)

        assertEquals(listOf("second"), programs.map { it.id })
        assertEquals(85, programs.single().recommendationScore)
    }

    @Test
    fun acceptsAnEmptyRankingWhenNoCandidateMeetsTheRecommendationMinimum() {
        client.reset(response())

        assertEquals(emptyList<SupportProgram>(), facade().rank(QUERY, candidates(), 5))
    }

    @Test
    fun rejectsMoreRankingsThanTheRequestedLimit() {
        client.reset(
            response(
                score("second", semantic = 40, total = 85, reason = "질의와 직접 관련"),
                score("first", semantic = 20, total = 65, reason = "일부 관련"),
            ),
        )

        val exception = assertThrows(AiServiceCallException::class.java) {
            facade().rank(QUERY, candidates(), 1)
        }

        assertEquals(AiServiceFailure.INVALID_RESPONSE, exception.failure)
    }

    @Test
    fun rejectsUnknownDuplicateAndAscendingProgramIds() {
        val invalidPayloads = listOf(
            response(score("unknown", 40, 85, "근거"), score("first", 20, 65, "근거 2")),
            response(score("first", 40, 85, "근거"), score("first", 20, 65, "근거 2")),
            response(score("first", 20, 65, "근거"), score("second", 40, 85, "근거 2")),
        )

        invalidPayloads.forEach { payload ->
            client.reset(payload)

            assertInvalidResponse()
        }
    }

    @Test
    fun rejectsRankingsThatDoNotMeetTheRecommendationMinimum() {
        val invalidPayloads = listOf(
            response(score("first", semantic = 19, total = 64, reason = "의미 관련성이 부족")),
            response(
                score(
                    "first",
                    semantic = 20,
                    total = 59,
                    reason = "전체 적합성이 부족",
                    applicationStatus = 5,
                    supportType = 4,
                ),
            ),
        )

        invalidPayloads.forEach { payload ->
            client.reset(payload)

            assertInvalidResponse()
        }
    }

    @Test
    fun rejectsAnIncompatibleTargetOrRegionEvenWhenTheTotalScoreIsHigh() {
        val invalidPayloads = listOf(
            response(
                score(
                    "first",
                    semantic = 40,
                    total = 65,
                    reason = "서울 AI 공고",
                    target = 0,
                    targetEligibility = AiSupportProgramEligibility.INCOMPATIBLE,
                ),
            ),
            response(
                score(
                    "first",
                    semantic = 40,
                    total = 75,
                    reason = "AI 기업 지원",
                    region = 0,
                    regionEligibility = AiSupportProgramEligibility.INCOMPATIBLE,
                ),
            ),
        )

        invalidPayloads.forEach { payload ->
            client.reset(payload)

            assertInvalidResponse()
        }
    }

    @Test
    fun acceptsUnknownEligibilityWhenTheCandidateOtherwiseMeetsTheRecommendationMinimum() {
        client.reset(
            response(
                score(
                    "first",
                    semantic = 40,
                    total = 60,
                    reason = "질의와 관련된 지원사업",
                    target = 0,
                    region = 5,
                    applicationStatus = 10,
                    supportType = 5,
                    targetEligibility = AiSupportProgramEligibility.UNKNOWN,
                    regionEligibility = AiSupportProgramEligibility.UNKNOWN,
                ),
            ),
        )

        assertEquals(listOf("first"), facade().rank(QUERY, candidates(), 5).map { it.id })
    }

    @Test
    fun rejectsWrongEchoVersionScoreSumAndReasons() {
        val validScores = arrayOf(
            score("second", 40, 85, "직접 관련"),
            score("first", 20, 65, "일부 관련"),
        )
        val invalidPayloads = listOf(
            response(*validScores).copy(originalQuery = "변조된 질의"),
            response(*validScores).copy(scoringVersion = "stale-version"),
            response(
                validScores[0].copy(totalScore = 84),
                validScores[1],
            ),
            response(
                validScores[0].copy(recommendationReasons = emptyList()),
                validScores[1],
            ),
        )

        invalidPayloads.forEach { payload ->
            client.reset(payload)

            assertInvalidResponse()
        }
    }

    private fun assertInvalidResponse() {
        val exception = assertThrows(AiServiceCallException::class.java) {
            facade().rank(QUERY, candidates(), 5)
        }
        assertEquals(AiServiceFailure.INVALID_RESPONSE, exception.failure)
    }

    private fun facade() = AiSupportProgramRankingFacade(client)

    private fun candidates() = listOf(
        CatalogSupportProgram(program("first"), "2026-08-20"),
        CatalogSupportProgram(program("second"), "2026-08-21"),
    )

    private fun program(id: String) = SupportProgram(
        id = id,
        sourceCode = "BIZINFO",
        title = "$id 지원사업",
        organization = "기관",
        summary = "$id 기업을 지원합니다.",
        categories = listOf("AI"),
        regions = listOf("서울"),
        targetDescription = "중소기업",
        applicationPeriod = "상시 접수",
        applicationStartDate = null,
        applicationEndDate = null,
        status = SupportProgramStatus.OPEN,
        sourceName = "기업마당",
        sourceUrl = "https://www.bizinfo.go.kr/$id",
        matchedReasons = emptyList(),
    )

    private fun response(vararg scores: AiScoredSupportProgramPayload) =
        AiSupportProgramRankingPayload(
            originalQuery = QUERY,
            scoringVersion = AiSupportProgramRankingFacade.SCORING_VERSION,
            rankings = scores.toList(),
        )

    private fun score(
        id: String,
        semantic: Int,
        total: Int,
        reason: String,
        target: Int = 20,
        region: Int = 10,
        applicationStatus: Int = 10,
        supportType: Int = 5,
        targetEligibility: AiSupportProgramEligibility = AiSupportProgramEligibility.MATCH,
        regionEligibility: AiSupportProgramEligibility = AiSupportProgramEligibility.MATCH,
    ) = AiScoredSupportProgramPayload(
        programId = id,
        semanticRelevance = semantic,
        targetFit = target,
        targetEligibility = targetEligibility,
        regionFit = region,
        regionEligibility = regionEligibility,
        applicationStatusFit = applicationStatus,
        supportTypeFit = supportType,
        totalScore = total,
        recommendationReasons = listOf(reason),
    )

    private companion object {
        const val QUERY = "서울 AI 지원사업"
    }

    private class StubRankingClient : AiSupportProgramRankingClient {
        val requests = mutableListOf<AiSupportProgramRankingRequest>()
        lateinit var response: AiSupportProgramRankingPayload

        override fun rankSupportPrograms(
            request: AiSupportProgramRankingRequest,
        ): AiSupportProgramRankingPayload {
            requests += request
            return response
        }

        fun reset(nextResponse: AiSupportProgramRankingPayload) {
            requests.clear()
            response = nextResponse
        }
    }
}
