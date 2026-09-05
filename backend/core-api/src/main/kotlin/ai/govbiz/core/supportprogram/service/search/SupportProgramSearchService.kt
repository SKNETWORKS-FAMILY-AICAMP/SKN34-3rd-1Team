package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.facade.AiSupportProgramRetrievalFacade
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchResult
import org.springframework.stereotype.Service

/** 공식 공고 후보와 LLM 점수화를 연결하는 검색 유스케이스입니다. */
@Service
class SupportProgramSearchService(
    private val supportProgramRepository: SupportProgramRepository,
    private val rankingFacade: SupportProgramRankingFacade,
    private val retrievalFacade: AiSupportProgramRetrievalFacade,
) {
    fun search(rawQuery: String?, acceptingOnly: Boolean): SupportProgramSearchResult {
        val query = rawQuery?.trim().orEmpty()
        val eligiblePrograms = supportProgramRepository
            .findPresentBizInfo()
            .asSequence()
            .filter { !acceptingOnly || it.program.status == SupportProgramStatus.OPEN }
            .toList()

        val programs = when {
            eligiblePrograms.isEmpty() -> emptyList()
            query.isBlank() -> eligiblePrograms
                .sortedWith(
                    compareByDescending<CatalogSupportProgram> { it.sortTimestamp }
                        .thenBy { it.program.id },
                )
                .take(SupportProgramRankingFacade.MAX_RESULTS)
                .map { it.program.copy(matchedReasons = emptyList(), recommendationScore = null) }
            else -> {
                val candidates = retrievalFacade.retrieve(query, eligiblePrograms)
                rankingFacade.rank(query, candidates, SupportProgramRankingFacade.MAX_RESULTS)
            }
        }

        return SupportProgramSearchResult(
            query = query,
            programs = java.util.List.copyOf(programs),
        )
    }
}
