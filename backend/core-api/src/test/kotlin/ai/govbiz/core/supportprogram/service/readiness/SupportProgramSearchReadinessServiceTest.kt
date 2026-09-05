package ai.govbiz.core.supportprogram.service.readiness

import ai.govbiz.core.supportprogram.domain.SupportProgramSyncOutcome
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncStatus
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchState
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SupportProgramSearchReadinessServiceTest {

    @Mock
    private lateinit var repository: SupportProgramRepository

    @Test
    fun reportsPreparingBeforeAnyCatalogSyncOutcomeExists() {
        doReturn(null).`when`(repository).findBizInfoSyncStatus()

        val result = service().get()

        assertEquals(SupportProgramSearchState.PREPARING, result.searchState)
        assertEquals(0, result.programCount)
        assertFalse(result.indexReady)
        assertNull(result.lastSuccessfulSyncAt)
        assertNull(result.lastFailedSyncAt)
    }

    @Test
    fun reportsSearchableForASuccessfullyPublishedEmptySnapshot() {
        doReturn(
            status(
                publishedGeneration = 4,
                programCount = 0,
                indexReady = true,
                outcome = SupportProgramSyncOutcome.SUCCESS,
                lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 30),
            ),
        ).`when`(repository).findBizInfoSyncStatus()

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE, result.searchState)
        assertEquals(0, result.programCount)
        assertTrue(result.indexReady)
        assertEquals("2026-09-05T09:30+09:00", result.lastSuccessfulSyncAt.toString())
        assertNull(result.lastFailedSyncAt)
    }

    @Test
    fun reportsSearchableForALegacySnapshotAdoptedAfterFullRepair() {
        doReturn(
            status(
                publishedGeneration = 0,
                programCount = 12,
                indexReady = true,
                outcome = SupportProgramSyncOutcome.NONE,
                lastSuccessfulSyncAt = null,
            ),
        ).`when`(repository).findBizInfoSyncStatus()

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE, result.searchState)
        assertEquals(12, result.programCount)
        assertTrue(result.indexReady)
        assertNull(result.lastSuccessfulSyncAt)
        assertNull(result.lastFailedSyncAt)
    }

    @Test
    fun keepsThePreviousReadySnapshotSearchableWhenTheLatestCatalogSyncFailed() {
        doReturn(
            status(
                publishedGeneration = 4,
                programCount = 12,
                indexReady = true,
                outcome = SupportProgramSyncOutcome.FAILURE,
                lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 0),
                lastFailedSyncAt = LocalDateTime.of(2026, 9, 5, 10, 0),
            ),
        ).`when`(repository).findBizInfoSyncStatus()

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_SYNC_FAILURE, result.searchState)
        assertEquals(12, result.programCount)
        assertTrue(result.indexReady)
        assertEquals("2026-09-05T10:00+09:00", result.lastFailedSyncAt.toString())
    }

    @Test
    fun reportsUnavailableWhenThePublishedSnapshotIsNotIndexReady() {
        doReturn(
            status(
                publishedGeneration = 4,
                programCount = 12,
                indexReady = false,
                outcome = SupportProgramSyncOutcome.FAILURE,
                lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 0),
                lastFailedSyncAt = LocalDateTime.of(2026, 9, 5, 10, 0),
            ),
        ).`when`(repository).findBizInfoSyncStatus()

        val result = service().get()

        assertEquals(SupportProgramSearchState.UNAVAILABLE, result.searchState)
        assertFalse(result.indexReady)
    }

    private fun service() = SupportProgramSearchReadinessService(repository, SEOUL_CLOCK)

    private fun status(
        publishedGeneration: Long,
        programCount: Int,
        indexReady: Boolean,
        outcome: SupportProgramSyncOutcome,
        lastSuccessfulSyncAt: LocalDateTime?,
        lastFailedSyncAt: LocalDateTime? = null,
    ) = SupportProgramSyncStatus(
        sourceCode = "BIZINFO",
        publishedGeneration = publishedGeneration,
        publishedCatalogFingerprint = "a".repeat(64),
        publishedProgramCount = programCount,
        indexReady = indexReady,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        lastFailedSyncAt = lastFailedSyncAt,
        lastSyncOutcome = outcome,
    )

    private companion object {
        val SEOUL_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-09-05T00:00:00Z"),
            ZoneId.of("Asia/Seoul"),
        )
    }
}
