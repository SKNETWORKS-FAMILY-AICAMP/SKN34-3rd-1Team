package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.AiServiceFailure
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramEvidenceClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceAnswerPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceAnswerRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceChunkRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceIndexPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceIndexRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceMatchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceSearchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramEvidenceSearchRequest
import ai.govbiz.core.supportprogram.service.dto.SupportProgramEvidenceAnswerStatus
import ai.govbiz.core.supportprogram.service.evidence.SupportProgramEvidenceChunk
import ai.govbiz.core.supportprogram.helper.SupportProgramContentHashHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class AiSupportProgramEvidenceFacadeTest {

    @Mock
    private lateinit var client: AiSupportProgramEvidenceClient

    @Test
    fun indexesRetrievesAnswersAndMapsOnlyCitedOfficialChunks() {
        val citedText = "공고 안내입니다. ".repeat(60) + "\n신청 방법: 온라인 접수입니다."
        val chunks = listOf(chunk(0, "첫 번째 근거"), chunk(1, citedText))
        val indexRequest = indexRequest(chunks)
        doReturn(AiSupportProgramEvidenceIndexPayload(2)).`when`(client).indexChunks(indexRequest)
        doReturn(
            AiSupportProgramEvidenceSearchPayload(
                QUESTION,
                listOf(match(chunks[1], 0.9), match(chunks[0], 0.8)),
            ),
        ).`when`(client).searchChunks(searchRequest(indexRequest))
        doReturn(
            AiSupportProgramEvidenceAnswerPayload(
                "신청 방법은 온라인 접수입니다.",
                "ANSWERED",
                listOf(chunks[1].id),
            ),
        ).`when`(client).answer(answerRequest(QUESTION, listOf(chunks[1], chunks[0])))

        val result = AiSupportProgramEvidenceFacade(client).answer(QUESTION, chunks, SOURCE_URL)

        assertEquals(SupportProgramEvidenceAnswerStatus.ANSWERED, result.answerStatus)
        assertEquals("신청 방법은 온라인 접수입니다.", result.answer)
        assertEquals(listOf(chunks[1].order), result.citations.map { it.chunkOrder })
        assertEquals(SOURCE_URL, result.citations.single().sourceUrl)
        assertEquals(citedText, result.citations.single().excerpt)
    }

    @Test
    fun rejectsIncompleteSearchAndUngroundedAnswerCitations() {
        val chunks = chunks()
        val indexRequest = indexRequest(chunks)
        doReturn(AiSupportProgramEvidenceIndexPayload(2)).`when`(client).indexChunks(indexRequest)
        doReturn(
            AiSupportProgramEvidenceSearchPayload(QUESTION, listOf(match(chunks[0], 0.9))),
        ).`when`(client).searchChunks(searchRequest(indexRequest))

        assertInvalid(chunks)

        doReturn(
            AiSupportProgramEvidenceSearchPayload(
                QUESTION,
                listOf(match(chunks[0], 0.9), match(chunks[1], 0.8)),
            ),
        ).`when`(client).searchChunks(searchRequest(indexRequest))
        doReturn(
            AiSupportProgramEvidenceAnswerPayload("근거 없는 답변", "ANSWERED", emptyList()),
        ).`when`(client).answer(answerRequest(QUESTION, chunks))

        assertInvalid(chunks)
    }

    private fun assertInvalid(chunks: List<SupportProgramEvidenceChunk>) {
        val exception = assertThrows(AiServiceCallException::class.java) {
            AiSupportProgramEvidenceFacade(client).answer(QUESTION, chunks, SOURCE_URL)
        }
        assertEquals(AiServiceFailure.INVALID_RESPONSE, exception.failure)
    }

    private fun match(chunk: SupportProgramEvidenceChunk, score: Double) =
        AiSupportProgramEvidenceMatchPayload(
            id = chunk.id,
            contentHash = chunk.contentHash,
            documentId = chunk.documentId,
            order = chunk.order,
            score = score,
        )

    private fun indexRequest(chunks: List<SupportProgramEvidenceChunk>) =
        AiSupportProgramEvidenceIndexRequest(chunks.map(::chunkRequest))

    private fun searchRequest(indexRequest: AiSupportProgramEvidenceIndexRequest) =
        AiSupportProgramEvidenceSearchRequest(
            question = QUESTION,
            eligibleChunks = indexRequest.chunks.map(AiSupportProgramEvidenceChunkRequest::reference),
            limit = 2,
        )

    private fun answerRequest(
        question: String,
        chunks: List<SupportProgramEvidenceChunk>,
    ) = AiSupportProgramEvidenceAnswerRequest(question, chunks.map(::chunkRequest).map { it.answerInput() })

    private fun chunkRequest(chunk: SupportProgramEvidenceChunk) =
        AiSupportProgramEvidenceChunkRequest(
            id = chunk.id,
            contentHash = chunk.contentHash,
            documentId = chunk.documentId,
            order = chunk.order,
            text = chunk.text,
        )

    private fun chunks() = listOf(chunk(0, "첫 번째 근거"), chunk(1, "두 번째 근거"))

    private fun chunk(order: Int, text: String): SupportProgramEvidenceChunk {
        val contentHash = SupportProgramContentHashHelper.sha256(text)
        return SupportProgramEvidenceChunk(
            id = SupportProgramContentHashHelper.sha256("chunk-$order"),
            contentHash = contentHash,
            documentId = "BIZINFO:PBLN_TEST",
            order = order,
            text = text,
        )
    }

    private companion object {
        const val QUESTION = "신청 방법이 무엇인가요?"
        const val SOURCE_URL = "https://www.bizinfo.go.kr/detail?id=PBLN_TEST"
    }
}
