package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.facade.SupportProgramCatalogFacade
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.facade.exception.SupportProgramCatalogFacadeException
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchResult
import org.springframework.stereotype.Service

/** 공식 공고 후보와 LLM 점수화를 연결하는 검색 유스케이스입니다. */
@Service
class SupportProgramSearchService(
    private val catalogFacade: SupportProgramCatalogFacade,
    private val rankingFacade: SupportProgramRankingFacade,
) {
    fun search(rawQuery: String?, acceptingOnly: Boolean): SupportProgramSearchResult {
        val query = rawQuery?.trim().orEmpty()
        val catalogPrograms = try {
            catalogFacade.load()
        } catch (exception: SupportProgramCatalogFacadeException) {
            throw SupportProgramSearchException.fromFacade(exception)
        }
        val candidates = catalogPrograms
            .asSequence()
            .filter { !acceptingOnly || it.program.status == SupportProgramStatus.OPEN }
            .sortedByDescending(CatalogSupportProgram::sortTimestamp)
            .take(SupportProgramRankingFacade.MAX_CANDIDATES)
            .toList()

        val programs = when {
            candidates.isEmpty() -> emptyList()
            query.isBlank() -> candidates
                .take(SupportProgramRankingFacade.MAX_RESULTS)
                .map { it.program.copy(matchedReasons = emptyList(), recommendationScore = null) }
            else -> rankingFacade.rank(query, candidates, SupportProgramRankingFacade.MAX_RESULTS)
        }

        return SupportProgramSearchResult(
            query = query,
            programs = java.util.List.copyOf(programs),
        )
    }
}
