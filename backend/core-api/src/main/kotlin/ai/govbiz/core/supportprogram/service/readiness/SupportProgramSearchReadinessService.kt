package ai.govbiz.core.supportprogram.service.readiness

import ai.govbiz.core.supportprogram.domain.SupportProgramSyncOutcome
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncStatus
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchReadinessResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchState
import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/** 공개된 기업마당 스냅샷과 마지막 동기화 결과를 화면이 구분할 수 있는 상태로 만듭니다. */
@Service
class SupportProgramSearchReadinessService(
    private val repository: SupportProgramRepository,
    @param:Qualifier("seoulClock") private val clock: Clock,
) {
    fun get(): SupportProgramSearchReadinessResult {
        val status = repository.findBizInfoSyncStatus() ?: return initialReadiness()
        return SupportProgramSearchReadinessResult(
            searchState = stateFor(status),
            programCount = status.publishedProgramCount,
            indexReady = status.indexReady,
            lastSuccessfulSyncAt = status.lastSuccessfulSyncAt?.toSeoulOffsetDateTime(),
            lastFailedSyncAt = status.lastFailedSyncAt?.toSeoulOffsetDateTime(),
        )
    }

    private fun initialReadiness() = SupportProgramSearchReadinessResult(
        searchState = SupportProgramSearchState.PREPARING,
        programCount = 0,
        indexReady = false,
        lastSuccessfulSyncAt = null,
        lastFailedSyncAt = null,
    )

    private fun stateFor(status: SupportProgramSyncStatus): SupportProgramSearchState =
        when {
            !status.indexReady -> SupportProgramSearchState.UNAVAILABLE
            status.lastSyncOutcome == SupportProgramSyncOutcome.FAILURE ->
                SupportProgramSearchState.SEARCHABLE_WITH_SYNC_FAILURE
            else -> SupportProgramSearchState.SEARCHABLE
        }

    private fun LocalDateTime.toSeoulOffsetDateTime(): OffsetDateTime =
        atZone(clock.zone).toOffsetDateTime()
}
