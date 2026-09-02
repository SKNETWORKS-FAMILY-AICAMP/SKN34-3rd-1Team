package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.client.bizinfo.BizInfoClient
import ai.govbiz.core.supportprogram.client.bizinfo.dto.BizInfoProgramPayload
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.facade.BizInfoSupportProgramCatalogFacade
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class SupportProgramSearchServiceTest {

    @Mock
    private lateinit var client: BizInfoClient

    private lateinit var ranking: RecordingSupportProgramRankingFacade

    @BeforeEach
    fun setUp() {
        ranking = RecordingSupportProgramRankingFacade()
    }

    @Test
    fun derivesDatesAndHonestStatusesForABlankLatestProgramsRequest() {
        Mockito.doReturn(
            listOf(
                payload("open", "<p>AI &amp; 기술<br>지원</p>", "2026-08-20 ~ 2026-09-11"),
                payload("rolling", "상시 사업", "2026-08-01 ~ 예산 소진시까지"),
                payload("upcoming", "예정 사업", "추후 공지"),
                payload("unknown", "상이 사업", "세부사업별 상이"),
                payload("closed", "종료 사업", "2026-07-01 ~ 2026-07-31"),
            ),
        ).`when`(client).fetchAll()

        val result = service().search("", false)
        val byId = result.programs.associateBy(SupportProgram::id)

        assertEquals(SupportProgramStatus.OPEN, byId.getValue("open").status)
        assertEquals("AI & 기술 지원", byId.getValue("open").summary)
        assertFalse(byId.getValue("open").summary.contains("<"))
        assertEquals("2026-08-20", byId.getValue("open").applicationStartDate.toString())
        assertEquals("2026-09-11", byId.getValue("open").applicationEndDate.toString())
        assertEquals(SupportProgramStatus.OPEN, byId.getValue("rolling").status)
        assertNull(byId.getValue("rolling").applicationEndDate)
        assertEquals(SupportProgramStatus.UPCOMING, byId.getValue("upcoming").status)
        assertEquals(SupportProgramStatus.UNKNOWN, byId.getValue("unknown").status)
        assertEquals(SupportProgramStatus.CLOSED, byId.getValue("closed").status)
        assertNull(byId.getValue("open").recommendationScore)
        assertEquals(emptyList<String>(), byId.getValue("open").matchedReasons)
        assertEquals(emptyList<RankingCall>(), ranking.calls)
    }

    @Test
    fun sendsFilteredOfficialCandidatesToLlmRankingAndReturnsItsResult() {
        val query = "서울에서 AI 창업기업이 받을 지원사업"
        Mockito.doReturn(
            listOf(
                payload("open", "AI 창업 지원", "상시 접수"),
                payload("closed", "지난 AI 지원", "2026-07-01 ~ 2026-07-31"),
            ),
        ).`when`(client).fetchAll()
        ranking.response = { candidates ->
            listOf(
                candidates.single().program.copy(
                    recommendationScore = 93,
                    matchedReasons = listOf("서울 AI 창업기업 대상"),
                ),
            )
        }

        val result = service().search(query, true)

        val rankedCandidates = ranking.calls.single().candidates
        assertEquals(listOf("open"), rankedCandidates.map { it.program.id })
        assertEquals(93, result.programs.single().recommendationScore)
        assertEquals(listOf("서울 AI 창업기업 대상"), result.programs.single().matchedReasons)
    }

    @Test
    fun capsTheTemporaryPreVectorCandidateWindowAtTwentyNewestPrograms() {
        Mockito.doReturn(
            (1..25).map { index ->
                payload("program-$index", "공고 $index", "상시 접수")
                    .copy(updatedAt = "2026-08-${index.toString().padStart(2, '0')} 10:00:00")
            },
        ).`when`(client).fetchAll()
        ranking.response = { emptyList() }

        service().search("기술 지원", false)

        val rankedCandidates = ranking.calls.single().candidates
        assertEquals(20, rankedCandidates.size)
        assertEquals("program-25", rankedCandidates.first().program.id)
        assertEquals("program-6", rankedCandidates.last().program.id)
    }

    @Test
    fun returnsAnImmutableResultList() {
        Mockito.doReturn(listOf(payload("open", "AI 지원", "상시 접수")))
            .`when`(client).fetchAll()

        val result = service().search("   ", true)

        assertThrows(UnsupportedOperationException::class.java) {
            (result.programs as MutableList<SupportProgram>).add(result.programs.single())
        }
    }

    private fun service() = SupportProgramSearchService(
        BizInfoSupportProgramCatalogFacade(client, CLOCK),
        ranking,
    )

    private fun payload(
        id: String,
        summary: String,
        period: String?,
    ) = BizInfoProgramPayload(
        "$id 공고",
        "https://www.bizinfo.go.kr/detail?id=$id",
        id,
        "중소벤처기업부",
        "수행기관",
        summary,
        "AI",
        "2026-08-20 10:00:00",
        period,
        "2026-08-21 10:00:00",
        "창업기업",
        "AI,서울",
        "온라인 신청",
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-24T03:00:00Z"),
            ZoneId.of("Asia/Seoul"),
        )
    }

    private data class RankingCall(
        val query: String,
        val candidates: List<CatalogSupportProgram>,
        val limit: Int,
    )

    private class RecordingSupportProgramRankingFacade : SupportProgramRankingFacade {
        val calls = mutableListOf<RankingCall>()
        var response: (List<CatalogSupportProgram>) -> List<SupportProgram> = { emptyList() }

        override fun rank(
            query: String,
            candidates: List<CatalogSupportProgram>,
            limit: Int,
        ): List<SupportProgram> {
            calls += RankingCall(query, candidates, limit)
            return response(candidates)
        }
    }
}
