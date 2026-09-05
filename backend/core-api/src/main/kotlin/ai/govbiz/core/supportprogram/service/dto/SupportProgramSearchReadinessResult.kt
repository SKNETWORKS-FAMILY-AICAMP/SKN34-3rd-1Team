package ai.govbiz.core.supportprogram.service.dto

import java.time.OffsetDateTime

/** 현재 공개 카탈로그와 그 검색 색인이 사용자 검색에 사용할 수 있는지 나타내는 결과입니다. */
data class SupportProgramSearchReadinessResult(
    val searchState: SupportProgramSearchState,
    val programCount: Int,
    val indexReady: Boolean,
    val lastSuccessfulSyncAt: OffsetDateTime?,
    val lastFailedSyncAt: OffsetDateTime?,
)

enum class SupportProgramSearchState {
    PREPARING,
    SEARCHABLE,
    SEARCHABLE_WITH_SYNC_FAILURE,
    UNAVAILABLE,
}
