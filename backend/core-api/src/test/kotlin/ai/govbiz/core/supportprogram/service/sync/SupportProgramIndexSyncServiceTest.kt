package ai.govbiz.core.supportprogram.service.sync

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexBatchPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexBatchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncOutcome
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramCatalogFingerprintHelper
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
    private val documents = programs.map(SupportProgramIndexDocumentMapper::fromCatalog)
    private val firstBatch = AiSupportProgramIndexBatchRequest(documents.take(16))
    private val lastBatch = AiSupportProgramIndexBatchRequest(documents.drop(16))

    @Test
    fun indexesAllBatchesAndIsSafeToRepeat() {
        doReturn(AiSupportProgramIndexBatchPayload(16)).`when`(client).indexBatch(firstBatch)
        doReturn(AiSupportProgramIndexBatchPayload(1)).`when`(client).indexBatch(lastBatch)
        val service = SupportProgramIndexSyncService(repository, client)

        assertEquals(17, service.indexSnapshot(programs))
        assertEquals(17, service.indexSnapshot(programs))

        val order = inOrder(client)
        repeat(2) {
            order.verify(client).indexBatch(firstBatch)
            order.verify(client).indexBatch(lastBatch)
        }
    }

    @Test
    fun indexesProgramsFromDifferentSourcesWithTheSameRawId() {
        val bizInfo = catalogProgram("SHARED")
        val other = bizInfo.copy(
            program = bizInfo.program.copy(
                sourceCode = "OTHER",
                sourceName = "다른 제공처",
                sourceUrl = "https://other.example/program/SHARED",
            ),
        )
        val snapshot = listOf(bizInfo, other)
        val request = AiSupportProgramIndexBatchRequest(
            snapshot.map(SupportProgramIndexDocumentMapper::fromCatalog),
        )
        doReturn(AiSupportProgramIndexBatchPayload(2)).`when`(client).indexBatch(request)
        val service = SupportProgramIndexSyncService(repository, client)

        assertEquals(2, service.indexSnapshot(snapshot))
        verify(client).indexBatch(request)
    }

    @Test
    fun failedLaterBatchDoesNotPublishACompleteIndexAndNextRepairRetriesTheSnapshot() {
        doReturn(programs).`when`(repository).findPresent()
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
            SupportProgramIndexSyncService(repository, client).indexSnapshot(programs)
        }

        verify(client, never()).indexBatch(lastBatch)
    }

    @Test
    fun emptyCatalogNeedsNoEmbeddingCall() {
        doReturn(emptyList<CatalogSupportProgram>()).`when`(repository).findPresent()

        assertEquals(0, SupportProgramIndexSyncService(repository, client).repair())
        verifyNoInteractions(client)
        verify(repository, never()).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(
            emptyList(),
        )
    }

    @Test
    fun bootstrapsANonemptyLegacySnapshotOnlyAfterItsFullRepairSucceeds() {
        val snapshot = listOf(catalogProgram("legacy-program"))
        val request = AiSupportProgramIndexBatchRequest(
            snapshot.map(SupportProgramIndexDocumentMapper::fromCatalog),
        )
        doReturn(snapshot).`when`(repository).findPresent()
        doReturn(null).`when`(repository).findBizInfoSyncStatus()
        doReturn(AiSupportProgramIndexBatchPayload(1)).`when`(client).indexBatch(request)
        doReturn(true).`when`(repository).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(snapshot)

        assertEquals(1, SupportProgramIndexSyncService(repository, client).repair())

        verify(repository).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(snapshot)
    }

    @Test
    fun doesNotBootstrapALegacySnapshotWhenItsRepairFails() {
        val snapshot = listOf(catalogProgram("legacy-failed-repair-program"))
        val request = AiSupportProgramIndexBatchRequest(
            snapshot.map(SupportProgramIndexDocumentMapper::fromCatalog),
        )
        doReturn(snapshot).`when`(repository).findPresent()
        doReturn(null).`when`(repository).findBizInfoSyncStatus()
        doThrow(AiServiceCallException.unavailable(null)).`when`(client).indexBatch(request)

        assertThrows(AiServiceCallException::class.java) {
            SupportProgramIndexSyncService(repository, client).repair()
        }

        verify(repository, never()).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(snapshot)
    }

    @Test
    fun marksTheStoredPublishedSnapshotReadyOnlyAfterItsFullRepairSucceeds() {
        val snapshot = listOf(catalogProgram("ready-program"))
        val request = AiSupportProgramIndexBatchRequest(
            snapshot.map(SupportProgramIndexDocumentMapper::fromCatalog),
        )
        val fingerprint = SupportProgramCatalogFingerprintHelper.calculate(snapshot)
        doReturn(snapshot).`when`(repository).findPresent()
        doReturn(status(generation = 31L, fingerprint = fingerprint, programCount = 1))
            .`when`(repository).findBizInfoSyncStatus()
        doReturn(AiSupportProgramIndexBatchPayload(1)).`when`(client).indexBatch(request)

        assertEquals(1, SupportProgramIndexSyncService(repository, client).repair())

        verify(repository).markBizInfoIndexReadyIfPublishedSnapshotMatches(31L, fingerprint, 1)
        verify(repository, never()).markBizInfoIndexNotReadyIfPublishedSnapshotMatches(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
        )
        verify(repository, never()).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(snapshot)
    }

    @Test
    fun marksOnlyTheSamePublishedSnapshotNotReadyWhenItsRepairFails() {
        val snapshot = listOf(catalogProgram("failed-repair-program"))
        val request = AiSupportProgramIndexBatchRequest(
            snapshot.map(SupportProgramIndexDocumentMapper::fromCatalog),
        )
        val fingerprint = SupportProgramCatalogFingerprintHelper.calculate(snapshot)
        val failure = AiServiceCallException.unavailable(null)
        doReturn(snapshot).`when`(repository).findPresent()
        doReturn(status(generation = 32L, fingerprint = fingerprint, programCount = 1))
            .`when`(repository).findBizInfoSyncStatus()
        doThrow(failure).`when`(client).indexBatch(request)
        doReturn(false).`when`(repository).markBizInfoIndexNotReadyIfPublishedSnapshotMatches(32L, fingerprint, 1)

        assertEquals(
            failure,
            assertThrows(AiServiceCallException::class.java) {
                SupportProgramIndexSyncService(repository, client).repair()
            },
        )

        verify(repository).markBizInfoIndexNotReadyIfPublishedSnapshotMatches(32L, fingerprint, 1)
        verify(repository, never()).markBizInfoIndexReadyIfPublishedSnapshotMatches(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
        )
        verify(repository, never()).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(snapshot)
    }

    @Test
    fun doesNotChangeReadinessWhenThePublishedMetadataDoesNotMatchTheSnapshotItIndexed() {
        val snapshot = listOf(catalogProgram("stale-repair-program"))
        val request = AiSupportProgramIndexBatchRequest(
            snapshot.map(SupportProgramIndexDocumentMapper::fromCatalog),
        )
        doReturn(snapshot).`when`(repository).findPresent()
        doReturn(status(generation = 33L, fingerprint = "f".repeat(64), programCount = 1))
            .`when`(repository).findBizInfoSyncStatus()
        doReturn(AiSupportProgramIndexBatchPayload(1)).`when`(client).indexBatch(request)

        assertEquals(1, SupportProgramIndexSyncService(repository, client).repair())

        verify(repository, never()).markBizInfoIndexReadyIfPublishedSnapshotMatches(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
        )
        verify(repository, never()).markBizInfoIndexNotReadyIfPublishedSnapshotMatches(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
        )
        verify(repository, never()).bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(snapshot)
    }

    private fun status(
        generation: Long,
        fingerprint: String,
        programCount: Int,
    ) = SupportProgramSyncStatus(
        sourceCode = "BIZINFO",
        publishedGeneration = generation,
        publishedCatalogFingerprint = fingerprint,
        publishedProgramCount = programCount,
        indexReady = false,
        lastSuccessfulSyncAt = null,
        lastFailedSyncAt = null,
        lastSyncOutcome = SupportProgramSyncOutcome.SUCCESS,
    )
}
