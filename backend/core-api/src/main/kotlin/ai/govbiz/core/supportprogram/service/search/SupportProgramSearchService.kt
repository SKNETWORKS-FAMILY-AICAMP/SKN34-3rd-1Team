package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.facade.AiSupportProgramRetrievalFacade
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchTrace
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
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
     * 평가 전용 호출입니다. 공개 검색 응답에는 노출하지 않고, 비어 있지 않은 질문에서 실제 의미 검색 후보와
     * 최종 추천 공고의 제공처 포함 식별자를 남깁니다.
     */
    fun searchWithTrace(rawQuery: String?, acceptingOnly: Boolean): SupportProgramSearchTrace {
        val execution = execute(rawQuery, acceptingOnly)
        require(execution.query.isNotBlank()) { "search trace requires a nonblank query" }
        return SupportProgramSearchTrace(
            result = execution.result,
            candidateIds = immutableCanonicalIds(execution.candidates.map(CatalogSupportProgram::program)),
            finalProgramIds = immutableCanonicalIds(execution.result.programs),
            presentProgramCount = execution.presentProgramCount,
            eligibleProgramCount = execution.eligibleProgramCount,
            eligibleCatalogFingerprint = eligibleCatalogFingerprint(execution.eligiblePrograms),
        )
    }

    private fun execute(rawQuery: String?, acceptingOnly: Boolean): SearchExecution {
        val query = rawQuery?.trim().orEmpty()
        val presentPrograms = supportProgramRepository.findPresentBizInfo()
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
        java.util.List.copyOf(programs.map { "${it.sourceCode}:${it.id}" })

    /** 검색 문서와 같은 ID·내용 해시로 현재 후보 집합의 재현 가능한 지문을 만듭니다. */
    private fun eligibleCatalogFingerprint(programs: List<CatalogSupportProgram>): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                programs
                    .asSequence()
                    .map(SupportProgramIndexDocumentMapper::fromBizInfo)
                    .map { document -> "${document.id}:${document.contentHash}" }
                    .sorted()
                    .joinToString("\n")
                    .toByteArray(StandardCharsets.UTF_8),
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
