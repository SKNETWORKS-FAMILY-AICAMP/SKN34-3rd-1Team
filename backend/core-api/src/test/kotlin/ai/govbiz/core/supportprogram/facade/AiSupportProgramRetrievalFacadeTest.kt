package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.AiServiceFailure
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexMatchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexSearchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexSearchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper.catalogProgram
import java.text.Normalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class AiSupportProgramRetrievalFacadeTest {
    @Mock
    private lateinit var client: AiSupportProgramIndexClient
    private val programs = (1..25).map { catalogProgram("program-$it") }
    private val documents = programs.map(SupportProgramIndexDocumentMapper::fromCatalog)
    private val request = AiSupportProgramIndexSearchRequest("서울 AI", documents.map { it.reference() }, 20)

    @Test
    fun sendsAllTwentyFiveCurrentVersionsAndKeepsSemanticOrderWhenNoKeywordsMatch() {
        val query = "unmatchedquery"
        val request = AiSupportProgramIndexSearchRequest(query, documents.map { it.reference() }, 20)
        val selectedIndexes = listOf(24, 0) + (1..18)
        val selectedMatches = selectedIndexes.mapIndexed { rank, index -> match(index, 1.0 - rank * 0.01) }
        doReturn(AiSupportProgramIndexSearchPayload(query, selectedMatches))
            .`when`(client).search(request)

        val result = AiSupportProgramRetrievalFacade(client).retrieve(query, programs)

        assertEquals(selectedIndexes.map { "program-${it + 1}" }, result.map { it.program.id })
        verify(client).search(request)
        assertThrows(UnsupportedOperationException::class.java) {
            (result as MutableList).clear()
        }
    }

    @Test
    fun restoresAnOlderKeywordCandidateOutsideSemanticTwentyWithAUniqueTwentyCandidateBudget() {
        val query = "quartz funding"
        val candidates = (1..25).map { index ->
            catalogProgram(
                "program-$index",
                summary = when (index) {
                    20 -> "quartz"
                    25 -> "quartz funding"
                    else -> "별도 공고"
                },
            ).let { if (index == 25) it.copy(sortTimestamp = "2020-01-01") else it }
        }
        stubSemantic(query, candidates, candidates.take(20))

        val result = AiSupportProgramRetrievalFacade(client).retrieve(query, candidates)
        val ids = result.map { it.program.id }

        assertEquals(20, result.size)
        assertEquals(20, ids.toSet().size)
        // 두 순위에 포함된 공고가 먼저 오고, 같은 RRF 점수에서는 의미 검색 순위를 우선한다.
        assertEquals(listOf("program-20", "program-1", "program-25"), ids.take(3))
        assertTrue(result.all { it in candidates })
    }

    @Test
    fun combinesBothRankingsAndUsesSemanticRankForEqualFusionScores() {
        val first = catalogProgram("first", "quartz").copy(sortTimestamp = "2026-08-01")
        val second = catalogProgram("second", "quartz").copy(sortTimestamp = "2026-08-02")
        val candidates = listOf(first, second, catalogProgram("third", "별도 공고"))
        stubSemantic("quartz", candidates, candidates)

        val result = AiSupportProgramRetrievalFacade(client).retrieve("quartz", candidates)

        // 의미 순위 first/second와 키워드 순위 second/first의 합은 같으므로 first가 먼저다.
        assertEquals(listOf("first", "second", "third"), result.map { it.program.id })
    }

    @Test
    fun keywordRanksUseDistinctNormalizedTokensThenRecencyAndCanonicalIdRegardlessOfInputOrder() {
        val query = "크롬 QUARTZ funding"
        val semantic = (1..20).map { catalogProgram("semantic-$it", "별도 공고") }
        val decomposed = Normalizer.normalize("크롬", Normalizer.Form.NFD)
        val strongest = catalogProgram("strongest", "$decomposed quartz funding")
            .copy(sortTimestamp = "2020-01-01")
        val newest = catalogProgram("newest", "크롬 quartz").copy(sortTimestamp = "2026-08-22")
        val firstTie = catalogProgram("SHARED", "quartz funding")
        val secondTie = firstTie.copy(program = firstTie.program.copy(sourceCode = "OTHER"))
        val repeated = catalogProgram("repeated", "quartz quartz quartz quartz")
        val candidates = semantic + listOf(repeated, secondTie, strongest, firstTie, newest)
        val reverse = candidates.reversed()
        stubSemantic(query, candidates, semantic)
        stubSemantic(query, reverse, semantic)
        val facade = AiSupportProgramRetrievalFacade(client)

        val result = facade.retrieve(query, candidates)
        val reversedResult = facade.retrieve(query, reverse)
        val lexicalIds = result.filter { it !in semantic }.map { it.program.sourceQualifiedId }

        assertEquals(result, reversedResult)
        assertEquals(
            listOf("BIZINFO:strongest", "BIZINFO:newest", "BIZINFO:SHARED", "OTHER:SHARED", "BIZINFO:repeated"),
            lexicalIds,
        )
        assertEquals(20, result.size)
    }

    @Test
    fun propagatesSemanticFailureEvenWhenTheCatalogHasKeywordMatches() {
        doThrow(AiServiceCallException.unavailable(null)).`when`(client).search(request)

        val failure = assertThrows(AiServiceCallException::class.java) {
            AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", programs)
        }

        assertEquals(AiServiceFailure.UNAVAILABLE, failure.failure)
        verify(client).search(request)
    }

    @Test
    fun returnsBothSourcesWhenTheyShareTheSameRawProgramId() {
        val bizInfo = programs.first().copy(
            program = programs.first().program.copy(id = "SHARED", sourceCode = "BIZINFO"),
        )
        val other = bizInfo.copy(
            program = bizInfo.program.copy(
                sourceCode = "OTHER",
                sourceName = "다른 제공처",
                sourceUrl = "https://other.example/program/SHARED",
            ),
        )
        val candidates = listOf(bizInfo, other)
        val documents = candidates.map(SupportProgramIndexDocumentMapper::fromCatalog)
        val request = AiSupportProgramIndexSearchRequest("서울 AI", documents.map { it.reference() }, 20)
        doReturn(
            AiSupportProgramIndexSearchPayload(
                "서울 AI",
                listOf(
                    AiSupportProgramIndexMatchPayload(documents[1].id, documents[1].contentHash, 0.9),
                    AiSupportProgramIndexMatchPayload(documents[0].id, documents[0].contentHash, 0.8),
                ),
            ),
        ).`when`(client).search(request)

        val result = AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", candidates)

        assertEquals(listOf("OTHER", "BIZINFO"), result.map { it.program.sourceCode })
        assertEquals(listOf("SHARED", "SHARED"), result.map { it.program.id })
        verify(client).search(request)
    }

    @Test
    fun rejectsMissingAndIncorrectFieldsUnknownIdsHashesDuplicatesAndUnsortedScores() {
        val validMatches = (0..19).map { match(it, 1.0 - it * 0.01) }
        val valid = validMatches.first()
        fun changedFirst(value: AiSupportProgramIndexMatchPayload?) =
            AiSupportProgramIndexSearchPayload("서울 AI", listOf(value) + validMatches.drop(1))
        val invalidResponses = listOf(
            AiSupportProgramIndexSearchPayload("different query", validMatches),
            AiSupportProgramIndexSearchPayload("서울 AI", null),
            changedFirst(null),
            changedFirst(valid.copy(id = "OTHER:program-1")),
            changedFirst(valid.copy(id = null)),
            changedFirst(valid.copy(contentHash = "outdated")),
            changedFirst(validMatches[1]),
            changedFirst(valid.copy(score = null)),
            changedFirst(valid.copy(score = Double.NaN)),
            changedFirst(valid.copy(score = Double.POSITIVE_INFINITY)),
            changedFirst(valid.copy(score = Double.NEGATIVE_INFINITY)),
            changedFirst(valid.copy(score = 0.1)),
            AiSupportProgramIndexSearchPayload("서울 AI", validMatches.dropLast(1)),
            AiSupportProgramIndexSearchPayload("서울 AI", (0..20).map { match(it, 0.5) }),
        )
        for (payload in invalidResponses) {
            doReturn(payload).`when`(client).search(request)
            val failure = assertThrows(AiServiceCallException::class.java) {
                AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", programs)
            }
            assertEquals(AiServiceFailure.INVALID_RESPONSE, failure.failure, payload.toString())
        }
    }

    @Test
    fun rejectsEmptySuccessResponseForAnEligibleCatalog() {
        doReturn(AiSupportProgramIndexSearchPayload("서울 AI", emptyList())).`when`(client).search(request)
        val failure = assertThrows(AiServiceCallException::class.java) {
            AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", programs)
        }
        assertEquals(AiServiceFailure.INVALID_RESPONSE, failure.failure)
        verify(client).search(request)
    }

    @Test
    fun doesNotCallTheClientForAnEmptyCatalog() {
        assertEquals(emptyList<Any>(), AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", emptyList()))
        verifyNoInteractions(client)
    }

    @Test
    fun refusesOversizedCatalogInsteadOfSilentlyTruncating() {
        val oversized = List(20_001) { programs.first() }
        val exception = assertThrows(AiServiceCallException::class.java) {
            AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", oversized)
        }
        assertEquals(AiServiceFailure.UNAVAILABLE, exception.failure)
        verifyNoInteractions(client)
    }

    private fun match(index: Int, score: Double) = AiSupportProgramIndexMatchPayload(
        documents[index].id, documents[index].contentHash, score,
    )

    private fun stubSemantic(
        query: String,
        candidates: List<CatalogSupportProgram>,
        selected: List<CatalogSupportProgram>,
    ) {
        val documents = candidates.map(SupportProgramIndexDocumentMapper::fromCatalog)
        val request = AiSupportProgramIndexSearchRequest(query, documents.map { it.reference() }, 20)
        val matches = selected.mapIndexed { index, candidate ->
            val document = SupportProgramIndexDocumentMapper.fromCatalog(candidate)
            AiSupportProgramIndexMatchPayload(document.id, document.contentHash, 1.0 - index * 0.01)
        }
        doReturn(AiSupportProgramIndexSearchPayload(query, matches)).`when`(client).search(request)
    }
}
