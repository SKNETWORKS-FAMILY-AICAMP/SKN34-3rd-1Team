package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexSearchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import org.springframework.stereotype.Component

/** 현재 DB 공고 버전으로 검색 범위를 제한하고 의미 검색 응답을 공고 후보로 변환합니다. */
@Component
class AiSupportProgramRetrievalFacade(private val client: AiSupportProgramIndexClient) {
    fun retrieve(query: String, eligiblePrograms: List<CatalogSupportProgram>): List<CatalogSupportProgram> {
        require(query.isNotBlank()) { "query must not be blank" }
        if (eligiblePrograms.isEmpty()) return emptyList()
        if (eligiblePrograms.size > SupportProgramIndexDocumentMapper.MAX_DOCUMENTS) {
            throw AiServiceCallException.unavailable(null)
        }
        val documents = eligiblePrograms.map(SupportProgramIndexDocumentMapper::fromCatalog)
        val programsById = documents.mapIndexed { index, document -> document.id to eligiblePrograms[index] }.toMap()
        check(programsById.size == eligiblePrograms.size) { "duplicate catalog identities" }
        val hashesById = documents.associate { it.id to it.contentHash }
        val payload = client.search(
            AiSupportProgramIndexSearchRequest(
                query,
                documents.map { it.reference() },
                SupportProgramRankingFacade.MAX_CANDIDATES,
            ),
        )
        fun invalid(): Nothing = throw AiServiceCallException.invalidResponse(
            "AI Service semantic search violated the internal contract", null,
        )
        if (payload.query != query) invalid()
        val matches = payload.matches ?: invalid()
        if (matches.size != minOf(SupportProgramRankingFacade.MAX_CANDIDATES, eligiblePrograms.size)) invalid()
        val seen = HashSet<String>()
        var previousScore = Double.POSITIVE_INFINITY
        val candidates = matches.map { nullableMatch ->
            val match = nullableMatch ?: invalid()
            val id = match.id ?: invalid()
            val candidate = programsById[id] ?: invalid()
            if (!seen.add(id) || match.contentHash != hashesById[id]) invalid()
            val score = match.score?.takeIf { it.isFinite() } ?: invalid()
            if (score > previousScore) invalid()
            previousScore = score
            candidate
        }
        return java.util.List.copyOf(candidates)
    }
}
