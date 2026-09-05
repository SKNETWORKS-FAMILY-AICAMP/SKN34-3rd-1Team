package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.AiServiceFailure
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexMatchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexSearchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexSearchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper.catalogProgram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
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
    fun sendsAllTwentyFiveCurrentVersionsAndReturnsOnlySemanticallySelectedPrograms() {
        val selectedIndexes = listOf(24, 0) + (1..18)
        val selectedMatches = selectedIndexes.mapIndexed { rank, index -> match(index, 1.0 - rank * 0.01) }
        doReturn(AiSupportProgramIndexSearchPayload("서울 AI", selectedMatches))
            .`when`(client).search(request)

        val result = AiSupportProgramRetrievalFacade(client).retrieve("서울 AI", programs)

        assertEquals(selectedIndexes.map { "program-${it + 1}" }, result.map { it.program.id })
        verify(client).search(request)
        assertThrows(UnsupportedOperationException::class.java) {
            (result as MutableList).clear()
        }
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
}
