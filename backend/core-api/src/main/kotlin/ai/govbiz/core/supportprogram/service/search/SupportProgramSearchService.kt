package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramStatusResolver
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.facade.AiSupportProgramRetrievalFacade
import ai.govbiz.core.supportprogram.helper.SupportProgramCatalogFingerprintHelper
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchTrace
import java.time.LocalDate
import org.springframework.stereotype.Service

/** 공식 공고 후보와 LLM 점수화를 연결하는 검색 유스케이스입니다. */
@Service
class SupportProgramSearchService(
    private val supportProgramRepository: SupportProgramRepository,
    private val rankingFacade: SupportProgramRankingFacade,
    private val retrievalFacade: AiSupportProgramRetrievalFacade,
) {
    fun search(rawQuery: String?, acceptingOnly: Boolean): SupportProgramSearchResult =
        execute(rawQuery, acceptingOnly).result

    /**
     * 평가 전용 호출입니다. 공개 검색 응답에는 노출하지 않고, 비어 있지 않은 질문에서 실제 결합 검색 후보와
     * 최종 추천 공고의 제공처 포함 식별자를 남깁니다.
     */
    fun searchWithTrace(rawQuery: String?, acceptingOnly: Boolean): SupportProgramSearchTrace =
        trace(execute(rawQuery, acceptingOnly))

    /** 평가 기준 날짜의 접수 상태로만 후보·최종 결과를 기록합니다. */
    fun searchWithTrace(
        rawQuery: String?,
        acceptingOnly: Boolean,
        referenceDate: LocalDate,
    ): SupportProgramSearchTrace = trace(execute(rawQuery, acceptingOnly, referenceDate))

    private fun trace(execution: SearchExecution): SupportProgramSearchTrace {
        require(execution.query.isNotBlank()) { "search trace requires a nonblank query" }
        return SupportProgramSearchTrace(
            result = execution.result,
            candidateIds = immutableCanonicalIds(execution.candidates.map(CatalogSupportProgram::program)),
            finalProgramIds = immutableCanonicalIds(execution.result.programs),
            presentProgramCount = execution.presentProgramCount,
            eligibleProgramCount = execution.eligibleProgramCount,
            eligibleCatalogFingerprint = SupportProgramCatalogFingerprintHelper.calculate(execution.eligiblePrograms),
        )
    }

    private fun execute(
        rawQuery: String?,
        acceptingOnly: Boolean,
        referenceDate: LocalDate? = null,
    ): SearchExecution {
        val query = rawQuery?.trim().orEmpty()
        val presentPrograms = (if (query.isBlank()) {
            supportProgramRepository.findPublishedPresent()
        } else {
            supportProgramRepository.findSearchablePresent()
        }).let { programs ->
            referenceDate?.let { date -> programs.map { it.withStatusAt(date) } } ?: programs
        }
        if (query.isNotBlank() && presentPrograms.isEmpty()) {
            val statuses = supportProgramRepository.findSyncStatuses()
            // 게시된 공고/미복구 기존 공고가 있는데 모든 색인이 불가하면 '검색 결과 없음'이 아닙니다.
            // 초기 빈 DB나 검색 가능한 제공처의 정상 0건 스냅샷은 기존 빈 결과를 유지합니다.
            if (statuses.none { it.indexReady } &&
                statuses.any { it.publishedGeneration != null || it.publishedProgramCount > 0 }
            ) {
                throw AiServiceCallException.unavailable(null)
            }
        }
        val eligiblePrograms = presentPrograms
            .asSequence()
            .filter { !acceptingOnly || it.program.status == SupportProgramStatus.OPEN }
            .toList()

        val candidates = when {
            eligiblePrograms.isEmpty() || query.isBlank() -> emptyList()
            else -> retrievalFacade.retrieve(query, eligiblePrograms)
        }

        val programs = when {
            eligiblePrograms.isEmpty() -> emptyList()
            query.isBlank() -> eligiblePrograms
                .sortedWith(
                    compareByDescending<CatalogSupportProgram> { it.sortTimestamp }
                        .thenBy { it.program.sourceCode }
                        .thenBy { it.program.id },
                )
                .take(SupportProgramRankingFacade.MAX_RESULTS)
                .map { it.program.copy(matchedReasons = emptyList(), recommendationScore = null) }
            else -> rankingFacade.rank(query, candidates, SupportProgramRankingFacade.MAX_RESULTS)
        }

        return SearchExecution(
            query = query,
            result = SupportProgramSearchResult(
                query = query,
                programs = java.util.List.copyOf(programs),
            ),
            candidates = java.util.List.copyOf(candidates),
            presentProgramCount = presentPrograms.size,
            eligibleProgramCount = eligiblePrograms.size,
            eligiblePrograms = java.util.List.copyOf(eligiblePrograms),
        )
    }

    private fun immutableCanonicalIds(programs: List<SupportProgram>): List<String> =
        java.util.List.copyOf(programs.map(SupportProgram::sourceQualifiedId))

    private fun CatalogSupportProgram.withStatusAt(referenceDate: LocalDate): CatalogSupportProgram =
        copy(
            program = program.copy(
                status = SupportProgramStatusResolver.resolve(
                    applicationPeriod = program.applicationPeriod,
                    applicationStartDate = program.applicationStartDate,
                    applicationEndDate = program.applicationEndDate,
                    today = referenceDate,
                ),
            ),
        )

    private data class SearchExecution(
        val query: String,
        val result: SupportProgramSearchResult,
        val candidates: List<CatalogSupportProgram>,
        val presentProgramCount: Int,
        val eligibleProgramCount: Int,
        val eligiblePrograms: List<CatalogSupportProgram>,
    )
}
