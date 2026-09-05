package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.facade.AiSupportProgramRetrievalFacade
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SupportProgramSearchServiceTest {

    @Mock
    private lateinit var supportProgramRepository: SupportProgramRepository

    @Mock
    private lateinit var retrieval: AiSupportProgramRetrievalFacade

    private lateinit var ranking: RecordingSupportProgramRankingFacade

    @BeforeEach
    fun setUp() {
        ranking = RecordingSupportProgramRankingFacade()
    }

    @Test
    fun returnsCatalogProgramsWithoutRankingForABlankLatestProgramsRequest() {
        Mockito.doReturn(
            listOf(
                catalogProgram(
                    id = "open",
                    summary = "AI 기술 지원",
                    applicationPeriod = "2026-08-20 ~ 2026-09-11",
                    applicationStartDate = LocalDate.of(2026, 8, 20),
                    applicationEndDate = LocalDate.of(2026, 9, 11),
                ),
                catalogProgram(id = "rolling", applicationPeriod = "상시 접수"),
                catalogProgram(id = "upcoming", status = SupportProgramStatus.UPCOMING),
                catalogProgram(id = "unknown", status = SupportProgramStatus.UNKNOWN),
                catalogProgram(id = "closed", status = SupportProgramStatus.CLOSED),
            ),
        ).`when`(supportProgramRepository).findPresentBizInfo()

        val result = service().search("", false)
        val byId = result.programs.associateBy(SupportProgram::id)

        assertEquals(SupportProgramStatus.OPEN, byId.getValue("open").status)
        assertEquals("AI 기술 지원", byId.getValue("open").summary)
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
        Mockito.verifyNoInteractions(retrieval)
        Mockito.verify(supportProgramRepository).findPresentBizInfo()
    }

    @Test
    fun sendsFilteredCatalogCandidatesToLlmRankingAndReturnsItsResult() {
        val query = "서울에서 AI 창업기업이 받을 지원사업"
        val open = catalogProgram(id = "open", summary = "AI 창업 지원")
        Mockito.doReturn(listOf(open)).`when`(retrieval).retrieve(query, listOf(open))
        Mockito.doReturn(
            listOf(
                open,
                catalogProgram(
                    id = "closed",
                    summary = "지난 AI 지원",
                    status = SupportProgramStatus.CLOSED,
                ),
            ),
        ).`when`(supportProgramRepository).findPresentBizInfo()
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
    fun returnsAnEmptyResultWhenNoRankedCandidateMeetsTheRecommendationMinimum() {
        val query = "서울 AI 창업기업이 받을 지원사업"
        val open = catalogProgram(id = "open", summary = "AI 창업 지원")
        Mockito.doReturn(listOf(open)).`when`(retrieval).retrieve(query, listOf(open))
        Mockito.doReturn(listOf(open)).`when`(supportProgramRepository).findPresentBizInfo()
        ranking.response = { emptyList() }

        val result = service().search(query, true)

        assertEquals(emptyList<SupportProgram>(), result.programs)
        assertEquals(1, ranking.calls.size)
    }

    @Test
    fun searchesAllCurrentProgramsAndCanRankAnOlderProgramBeyondThePreviousTwentyNewest() {
        val programs = (1..25).map { index ->
                catalogProgram(
                    id = "program-$index",
                    title = if (index == 1) "서울 AI 기술 지원" else "수출 공고 $index",
                    sortTimestamp = "2026-08-${index.toString().padStart(2, '0')} 10:00:00",
                )
            }
        Mockito.doReturn(programs).`when`(supportProgramRepository).findPresentBizInfo()
        Mockito.doReturn(listOf(programs.first())).`when`(retrieval).retrieve("서울 AI", programs)
        ranking.response = { it.map { candidate -> candidate.program } }

        val result = service().search("서울 AI", false)

        val rankedCandidates = ranking.calls.single().candidates
        assertEquals(listOf("program-1"), rankedCandidates.map { it.program.id })
        assertEquals("program-1", result.programs.single().id)
        Mockito.verify(retrieval).retrieve("서울 AI", programs)
    }

    @Test
    fun changingTheQueryChangesTheSemanticCandidatesBeforeRanking() {
        val programs = listOf(catalogProgram("ai"), catalogProgram("export"))
        Mockito.doReturn(programs).`when`(supportProgramRepository).findPresentBizInfo()
        Mockito.doReturn(listOf(programs.first())).`when`(retrieval).retrieve("AI", programs)
        Mockito.doReturn(listOf(programs.last())).`when`(retrieval).retrieve("수출", programs)
        ranking.response = { it.map { candidate -> candidate.program } }

        assertEquals("ai", service().search("AI", false).programs.single().id)
        assertEquals("export", service().search("수출", false).programs.single().id)
    }

    @Test
    fun filtersClosedAndUpcomingBeforeSemanticCandidateSelection() {
        val open = catalogProgram("old-open", sortTimestamp = "2020-01-01")
        val programs = (1..25).map { catalogProgram("closed-$it", status = SupportProgramStatus.CLOSED) } +
            catalogProgram("upcoming", status = SupportProgramStatus.UPCOMING) + open
        Mockito.doReturn(programs).`when`(supportProgramRepository).findPresentBizInfo()
        Mockito.doReturn(listOf(open)).`when`(retrieval).retrieve("AI", listOf(open))
        ranking.response = { it.map { candidate -> candidate.program } }

        assertEquals("old-open", service().search("AI", true).programs.single().id)
        Mockito.verify(retrieval).retrieve("AI", listOf(open))
    }

    @Test
    fun propagatesIndexNotReadyInsteadOfFallingBackToNewestPrograms() {
        val programs = listOf(catalogProgram("open"))
        Mockito.doReturn(programs).`when`(supportProgramRepository).findPresentBizInfo()
        Mockito.doThrow(ai.govbiz.core._common.exception.AiServiceCallException.unavailable(null))
            .`when`(retrieval).retrieve("AI", programs)

        assertThrows(ai.govbiz.core._common.exception.AiServiceCallException::class.java) {
            service().search("AI", true)
        }
        assertEquals(emptyList<RankingCall>(), ranking.calls)
    }

    @Test
    fun usesProgramIdAsATieBreakerForProgramsWithTheSameSortTimestamp() {
        Mockito.doReturn(
            listOf(
                catalogProgram(id = "PBLN_B"),
                catalogProgram(id = "PBLN_A"),
            ),
        ).`when`(supportProgramRepository).findPresentBizInfo()

        val result = service().search("", false)

        assertEquals(listOf("PBLN_A", "PBLN_B"), result.programs.map(SupportProgram::id))
    }

    @Test
    fun returnsAnImmutableResultList() {
        Mockito.doReturn(listOf(catalogProgram(id = "open")))
            .`when`(supportProgramRepository).findPresentBizInfo()

        val result = service().search("   ", true)

        assertThrows(UnsupportedOperationException::class.java) {
            (result.programs as MutableList<SupportProgram>).add(result.programs.single())
        }
    }

    private fun service() = SupportProgramSearchService(
        supportProgramRepository,
        ranking,
        retrieval,
    )

    private fun catalogProgram(
        id: String,
        title: String = "$id 공고",
        summary: String = "AI 지원",
        status: SupportProgramStatus = SupportProgramStatus.OPEN,
        applicationPeriod: String = "상시 접수",
        applicationStartDate: LocalDate? = null,
        applicationEndDate: LocalDate? = null,
        sortTimestamp: String = "2026-08-21 10:00:00",
    ) = CatalogSupportProgram(
        program = SupportProgram(
            id = id,
            sourceCode = "BIZINFO",
            title = title,
            organization = "수행기관",
            summary = summary,
            categories = listOf("AI"),
            regions = listOf("서울"),
            targetDescription = "중소기업",
            applicationPeriod = applicationPeriod,
            applicationStartDate = applicationStartDate,
            applicationEndDate = applicationEndDate,
            status = status,
            sourceName = "기업마당",
            sourceUrl = "https://www.bizinfo.go.kr/detail?id=$id",
            matchedReasons = emptyList(),
            recommendationScore = null,
        ),
        sortTimestamp = sortTimestamp,
    )

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
