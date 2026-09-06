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
import org.junit.jupiter.api.Assertions.assertTrue
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
        ).`when`(supportProgramRepository).findPublishedPresent()

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
        Mockito.verify(supportProgramRepository).findPublishedPresent()
        Mockito.verify(supportProgramRepository, Mockito.never()).findSearchablePresent()
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
        ).`when`(supportProgramRepository).findSearchablePresent()
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
        Mockito.doReturn(listOf(open)).`when`(supportProgramRepository).findSearchablePresent()
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
        Mockito.doReturn(programs).`when`(supportProgramRepository).findSearchablePresent()
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
        Mockito.doReturn(programs).`when`(supportProgramRepository).findSearchablePresent()
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
        Mockito.doReturn(programs).`when`(supportProgramRepository).findSearchablePresent()
        Mockito.doReturn(listOf(open)).`when`(retrieval).retrieve("AI", listOf(open))
        ranking.response = { it.map { candidate -> candidate.program } }

        assertEquals("old-open", service().search("AI", true).programs.single().id)
        Mockito.verify(retrieval).retrieve("AI", listOf(open))
    }

    @Test
    fun propagatesIndexNotReadyInsteadOfFallingBackToNewestPrograms() {
        val programs = listOf(catalogProgram("open"))
        Mockito.doReturn(programs).`when`(supportProgramRepository).findSearchablePresent()
        Mockito.doThrow(ai.govbiz.core._common.exception.AiServiceCallException.unavailable(null))
            .`when`(retrieval).retrieve("AI", programs)

        assertThrows(ai.govbiz.core._common.exception.AiServiceCallException::class.java) {
            service().search("AI", true)
        }
        assertEquals(emptyList<RankingCall>(), ranking.calls)
    }

    @Test
    fun usesSourceQualifiedIdentityAsATieBreakerForProgramsWithTheSameSortTimestamp() {
        val other = catalogProgram(id = "SHARED", sourceCode = "OTHER")
        val bizInfo = catalogProgram(id = "SHARED", sourceCode = "BIZINFO")
        Mockito.doReturn(
            listOf(other, bizInfo),
        ).`when`(supportProgramRepository).findPublishedPresent()

        val result = service().search("", false)

        assertEquals(listOf("BIZINFO", "OTHER"), result.programs.map(SupportProgram::sourceCode))
        assertEquals(listOf("SHARED", "SHARED"), result.programs.map(SupportProgram::id))
    }

    @Test
    fun returnsAnImmutableResultList() {
        Mockito.doReturn(listOf(catalogProgram(id = "open")))
            .`when`(supportProgramRepository).findPublishedPresent()

        val result = service().search("   ", true)

        assertThrows(UnsupportedOperationException::class.java) {
            (result.programs as MutableList<SupportProgram>).add(result.programs.single())
        }
    }

    @Test
    fun capturesCanonicalSemanticCandidatesAndFinalProgramsForEvaluation() {
        val query = "서울 AI 창업 지원"
        val open = catalogProgram(id = "PBLN_OPEN", summary = "서울 AI 창업 지원")
        val closed = catalogProgram(id = "PBLN_CLOSED", status = SupportProgramStatus.CLOSED)
        Mockito.doReturn(listOf(open, closed)).`when`(supportProgramRepository).findSearchablePresent()
        Mockito.doReturn(listOf(open)).`when`(retrieval).retrieve(query, listOf(open))
        ranking.response = { candidates ->
            listOf(candidates.single().program.copy(recommendationScore = 91, matchedReasons = listOf("서울 AI 대상")))
        }

        val trace = service().searchWithTrace(query, acceptingOnly = true)

        assertEquals(query, trace.result.query)
        assertEquals(listOf("BIZINFO:PBLN_OPEN"), trace.candidateIds)
        assertEquals(listOf("BIZINFO:PBLN_OPEN"), trace.finalProgramIds)
        assertEquals(2, trace.presentProgramCount)
        assertEquals(1, trace.eligibleProgramCount)
        assertTrue(trace.eligibleCatalogFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun usesTheEvaluationReferenceDateForTraceEligibility() {
        val openOnReferenceDate = catalogProgram(
            id = "PBLN_OPEN_ON_REFERENCE_DATE",
            status = SupportProgramStatus.CLOSED,
            applicationPeriod = "2026-09-01 ~ 2026-09-06",
            applicationStartDate = LocalDate.of(2026, 9, 1),
            applicationEndDate = LocalDate.of(2026, 9, 6),
        )
        val closedOnReferenceDate = catalogProgram(
            id = "PBLN_CLOSED_ON_REFERENCE_DATE",
            status = SupportProgramStatus.OPEN,
            applicationPeriod = "2026-08-01 ~ 2026-09-04",
            applicationStartDate = LocalDate.of(2026, 8, 1),
            applicationEndDate = LocalDate.of(2026, 9, 4),
        )
        val expectedOpenOnReferenceDate = openOnReferenceDate.copy(
            program = openOnReferenceDate.program.copy(status = SupportProgramStatus.OPEN),
        )
        Mockito.doReturn(listOf(openOnReferenceDate, closedOnReferenceDate))
            .`when`(supportProgramRepository)
            .findSearchablePresent()
        Mockito.doReturn(listOf(expectedOpenOnReferenceDate))
            .`when`(retrieval)
            .retrieve("서울 AI", listOf(expectedOpenOnReferenceDate))
        ranking.response = { candidates ->
            candidates.map { candidate -> candidate.program.copy(recommendationScore = 90) }
        }

        val trace = service().searchWithTrace(
            rawQuery = "서울 AI",
            acceptingOnly = true,
            referenceDate = LocalDate.of(2026, 9, 5),
        )

        assertEquals(listOf("BIZINFO:PBLN_OPEN_ON_REFERENCE_DATE"), trace.candidateIds)
        assertEquals(SupportProgramStatus.OPEN, trace.result.programs.single().status)
        assertEquals(1, trace.eligibleProgramCount)
    }

    @Test
    fun keepsSameRawIdsFromDifferentSourcesDistinctInSearchTrace() {
        val query = "서울 AI 지원"
        val bizInfo = catalogProgram(id = "SHARED", sourceCode = "BIZINFO")
        val other = catalogProgram(id = "SHARED", sourceCode = "OTHER")
        val candidates = listOf(other, bizInfo)
        Mockito.doReturn(candidates).`when`(supportProgramRepository).findSearchablePresent()
        Mockito.doReturn(candidates).`when`(retrieval).retrieve(query, candidates)
        ranking.response = { selected ->
            selected.map { candidate ->
                candidate.program.copy(recommendationScore = 90, matchedReasons = listOf("서울 AI 관련"))
            }
        }

        val trace = service().searchWithTrace(query, acceptingOnly = true)

        assertEquals(listOf("OTHER:SHARED", "BIZINFO:SHARED"), trace.candidateIds)
        assertEquals(listOf("OTHER:SHARED", "BIZINFO:SHARED"), trace.finalProgramIds)
        assertEquals(listOf("OTHER", "BIZINFO"), trace.result.programs.map(SupportProgram::sourceCode))
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
        sourceCode: String = "BIZINFO",
        sortTimestamp: String = "2026-08-21 10:00:00",
    ) = CatalogSupportProgram(
        program = SupportProgram(
            id = id,
            sourceCode = sourceCode,
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
            sourceName = if (sourceCode == "BIZINFO") "기업마당" else sourceCode,
            sourceUrl = "https://${sourceCode.lowercase()}.example/detail?id=$id",
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
