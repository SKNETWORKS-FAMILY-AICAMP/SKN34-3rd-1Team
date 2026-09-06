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
        stubStatuses()

        val result = service().get()

        assertEquals(SupportProgramSearchState.PREPARING, result.searchState)
        assertEquals(0, result.programCount)
        assertFalse(result.indexReady)
        assertNull(result.lastSuccessfulSyncAt)
        assertNull(result.lastFailedSyncAt)
        assertEquals(listOf("BIZINFO"), result.sources.map { it.sourceCode })
    }

    @Test
    fun reportsSearchableForASuccessfullyPublishedEmptySnapshot() {
        stubStatuses(
            status(
                publishedGeneration = 4,
                programCount = 0,
                indexReady = true,
                outcome = SupportProgramSyncOutcome.SUCCESS,
                lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 30),
            ),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE, result.searchState)
        assertEquals(0, result.programCount)
        assertTrue(result.indexReady)
        assertEquals("2026-09-05T09:30+09:00", result.lastSuccessfulSyncAt.toString())
        assertNull(result.lastFailedSyncAt)
    }

    @Test
    fun reportsSearchableForALegacySnapshotAdoptedAfterFullRepair() {
        stubStatuses(
            status(
                publishedGeneration = 0,
                programCount = 12,
                indexReady = true,
                outcome = SupportProgramSyncOutcome.NONE,
                lastSuccessfulSyncAt = null,
            ),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE, result.searchState)
        assertEquals(12, result.programCount)
        assertTrue(result.indexReady)
        assertNull(result.lastSuccessfulSyncAt)
        assertNull(result.lastFailedSyncAt)
    }

    @Test
    fun keepsThePreviousReadySnapshotSearchableWhenTheLatestCatalogSyncFailed() {
        stubStatuses(
            status(
                publishedGeneration = 4,
                programCount = 12,
                indexReady = true,
                outcome = SupportProgramSyncOutcome.FAILURE,
                lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 0),
                lastFailedSyncAt = LocalDateTime.of(2026, 9, 5, 10, 0),
            ),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_SYNC_FAILURE, result.searchState)
        assertEquals(12, result.programCount)
        assertTrue(result.indexReady)
        assertEquals("2026-09-05T10:00+09:00", result.lastFailedSyncAt.toString())
    }

    @Test
    fun reportsUnavailableWhenThePublishedSnapshotIsNotIndexReady() {
        stubStatuses(
            status(
                publishedGeneration = 4,
                programCount = 12,
                indexReady = false,
                outcome = SupportProgramSyncOutcome.FAILURE,
                lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 0),
                lastFailedSyncAt = LocalDateTime.of(2026, 9, 5, 10, 0),
            ),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.UNAVAILABLE, result.searchState)
        assertFalse(result.indexReady)
        assertEquals(0, result.programCount)
        assertEquals(12, result.sources.single().programCount)
    }

    @Test
    fun searchesTheOtherProviderEvenBeforeBizInfoHasStarted() {
        stubStatuses(readyStatus("KSTARTUP", 8))

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_PARTIAL_SOURCES, result.searchState)
        assertTrue(result.indexReady)
        assertEquals(8, result.programCount)
        assertEquals(listOf("기업마당", "K-Startup"), result.sources.map { it.sourceName })
        assertEquals(SupportProgramSearchState.PREPARING, result.sources.first().searchState)
        assertEquals(SupportProgramSearchState.SEARCHABLE, result.sources.last().searchState)
    }

    @Test
    fun countsOnlyReadyProviderProgramsAndPreservesEachFailure() {
        val failedAt = LocalDateTime.of(2026, 9, 5, 11, 0)
        stubStatuses(
            readyStatus("BIZINFO", 12).copy(
                indexReady = false,
                lastSyncOutcome = SupportProgramSyncOutcome.FAILURE,
                lastFailedSyncAt = failedAt,
            ),
            readyStatus("KSTARTUP", 8),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_PARTIAL_SOURCES, result.searchState)
        assertEquals(8, result.programCount)
        assertEquals(12, result.sources.first().programCount)
        assertEquals("2026-09-05T11:00+09:00", result.lastFailedSyncAt.toString())
        assertEquals(result.lastFailedSyncAt, result.sources.first().lastFailedSyncAt)
        assertNull(result.sources.last().lastFailedSyncAt)
    }

    @Test
    fun keepsAllReadySourcesSearchableDespiteANewCollectionFailure() {
        stubStatuses(
            readyStatus("BIZINFO", 12),
            readyStatus("KSTARTUP", 8).copy(lastSyncOutcome = SupportProgramSyncOutcome.FAILURE),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_SYNC_FAILURE, result.searchState)
        assertEquals(20, result.programCount)
        assertTrue(result.sources.all { it.indexReady })
    }

    @Test
    fun pendingNewProviderDoesNotBlockAnExistingReadyProvider() {
        stubStatuses(
            readyStatus("BIZINFO", 12),
            readyStatus("KSTARTUP", 0).copy(
                indexReady = false,
                publishedGeneration = null,
                publishedCatalogFingerprint = null,
                lastSyncOutcome = SupportProgramSyncOutcome.NONE,
                lastSuccessfulSyncAt = null,
            ),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_PARTIAL_SOURCES, result.searchState)
        assertEquals(SupportProgramSearchState.PREPARING, result.sources.last().searchState)
        assertEquals(12, result.programCount)
    }

    private fun stubStatuses(vararg statuses: SupportProgramSyncStatus) {
        doReturn(statuses.toList()).`when`(repository).findSyncStatuses()
    }

    @Test
    fun includesUnverifiedLegacySourceAsUnavailableInsteadOfClaimingAllSourcesAreReady() {
        stubStatuses(
            readyStatus("BIZINFO", 12),
            readyStatus("OTHER", 3).copy(
                indexReady = false,
                publishedGeneration = null,
                publishedCatalogFingerprint = null,
                lastSyncOutcome = SupportProgramSyncOutcome.NONE,
                lastSuccessfulSyncAt = null,
            ),
        )

        val result = service().get()

        assertEquals(SupportProgramSearchState.SEARCHABLE_WITH_PARTIAL_SOURCES, result.searchState)
        assertEquals(12, result.programCount)
        assertEquals(SupportProgramSearchState.UNAVAILABLE, result.sources.last().searchState)
        assertEquals(3, result.sources.last().programCount)
        assertNull(result.sources.last().lastSuccessfulSyncAt)
        assertNull(result.sources.last().lastFailedSyncAt)
    }

    private fun readyStatus(sourceCode: String, count: Int) = status(
        publishedGeneration = 1,
        programCount = count,
        indexReady = true,
        outcome = SupportProgramSyncOutcome.SUCCESS,
        lastSuccessfulSyncAt = LocalDateTime.of(2026, 9, 5, 9, 0),
    ).copy(sourceCode = sourceCode)

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
