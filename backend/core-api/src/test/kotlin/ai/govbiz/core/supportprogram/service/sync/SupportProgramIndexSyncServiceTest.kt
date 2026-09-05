package ai.govbiz.core.supportprogram.service.sync

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexBatchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexBatchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper.catalogProgram
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SupportProgramIndexSyncServiceTest {
    @Mock
    private lateinit var repository: SupportProgramRepository
    @Mock
    private lateinit var client: AiSupportProgramIndexClient
    private val programs = (1..17).map { catalogProgram("program-$it") }
    private val documents = programs.map(SupportProgramIndexDocumentMapper::fromBizInfo)
    private val firstBatch = AiSupportProgramIndexBatchRequest(documents.take(16))
    private val lastBatch = AiSupportProgramIndexBatchRequest(documents.drop(16))

    @Test
    fun indexesAllBatchesAndIsSafeToRepeat() {
        doReturn(AiSupportProgramIndexBatchPayload(16)).`when`(client).indexBatch(firstBatch)
        doReturn(AiSupportProgramIndexBatchPayload(1)).`when`(client).indexBatch(lastBatch)
        val service = SupportProgramIndexSyncService(repository, client)

        assertEquals(17, service.indexBizInfoSnapshot(programs))
        assertEquals(17, service.indexBizInfoSnapshot(programs))

        val order = inOrder(client)
        repeat(2) {
            order.verify(client).indexBatch(firstBatch)
            order.verify(client).indexBatch(lastBatch)
        }
    }

    @Test
    fun failedLaterBatchDoesNotPublishACompleteIndexAndNextRepairRetriesTheSnapshot() {
        doReturn(programs).`when`(repository).findPresentBizInfo()
        doReturn(AiSupportProgramIndexBatchPayload(16)).`when`(client).indexBatch(firstBatch)
        doThrow(AiServiceCallException.unavailable(null)).doReturn(AiSupportProgramIndexBatchPayload(1))
            .`when`(client).indexBatch(lastBatch)
        val scheduler = SupportProgramIndexSyncScheduler(SupportProgramIndexSyncService(repository, client))

        scheduler.synchronize()
        scheduler.synchronize()

        verify(client, times(2)).indexBatch(firstBatch)
        verify(client, times(2)).indexBatch(lastBatch)
    }

    @Test
    fun refusesToAcceptAPartialBatchAcknowledgement() {
        doReturn(AiSupportProgramIndexBatchPayload(15)).`when`(client).indexBatch(firstBatch)

        assertThrows(AiServiceCallException::class.java) {
            SupportProgramIndexSyncService(repository, client).indexBizInfoSnapshot(programs)
        }

        verify(client, never()).indexBatch(lastBatch)
    }

    @Test
    fun emptyCatalogNeedsNoEmbeddingCall() {
        doReturn(emptyList<CatalogSupportProgram>()).`when`(repository).findPresentBizInfo()

        assertEquals(0, SupportProgramIndexSyncService(repository, client).repair())
        verifyNoInteractions(client)
    }
}
