package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.AiServiceFailure
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramRankingClient
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
    fun rejectsUnknownDuplicateMissingAndAscendingProgramIds() {
        val invalidPayloads = listOf(
            response(score("unknown", 40, 85, "근거"), score("first", 20, 65, "근거 2")),
            response(score("first", 40, 85, "근거"), score("first", 20, 65, "근거 2")),
            response(score("first", 40, 85, "근거")),
            response(score("first", 20, 65, "근거"), score("second", 40, 85, "근거 2")),
        )

        invalidPayloads.forEach { payload ->
            client.reset(payload)

            assertInvalidResponse()
        }
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
    ) = AiScoredSupportProgramPayload(
        programId = id,
        semanticRelevance = semantic,
        targetFit = 20,
        regionFit = 10,
        applicationStatusFit = 10,
        supportTypeFit = 5,
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
