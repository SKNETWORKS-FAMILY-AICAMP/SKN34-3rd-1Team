package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram

/** 검색 유스케이스에 검증된 공고 후보 점수화를 제공하는 Facade 계약입니다. */
fun interface SupportProgramRankingFacade {
    fun rank(
        query: String,
        candidates: List<CatalogSupportProgram>,
        limit: Int,
    ): List<SupportProgram>

    companion object {
        const val MAX_CANDIDATES = 20
        const val MAX_RESULTS = 5
    }
}
