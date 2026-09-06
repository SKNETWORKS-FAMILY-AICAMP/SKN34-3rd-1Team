package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexDocumentRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexSearchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import java.text.Normalizer
import java.util.Locale
import org.springframework.stereotype.Component

/** 현재 DB 공고 버전에서 검증한 의미 검색과 키워드 순위를 결합해 점수화 후보를 고릅니다. */
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
        val semanticIds = matches.map { nullableMatch ->
            val match = nullableMatch ?: invalid()
            val id = match.id ?: invalid()
            if (id !in programsById) invalid()
            if (!seen.add(id) || match.contentHash != hashesById[id]) invalid()
            val score = match.score?.takeIf { it.isFinite() } ?: invalid()
            if (score > previousScore) invalid()
            previousScore = score
            id
        }
        val keywordIds = keywordCandidates(query, documents, programsById)
        val candidateIds = combineRanks(semanticIds, keywordIds)
        return java.util.List.copyOf(candidateIds.map(programsById::getValue))
    }

    private fun keywordCandidates(
        query: String,
        documents: List<AiSupportProgramIndexDocumentRequest>,
        programsById: Map<String, CatalogSupportProgram>,
    ): List<String> {
        val queryTokens = tokenize(query)
        return documents.map { document ->
            document.id to tokenize(document.text).count(queryTokens::contains)
        }.filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { programsById.getValue(it.first).sortTimestamp }
                    .thenBy { it.first },
            )
            .take(SupportProgramRankingFacade.MAX_CANDIDATES)
            .map { it.first }
    }

    private fun combineRanks(semanticIds: List<String>, keywordIds: List<String>): List<String> {
        val semanticRanks = semanticIds.withIndex().associate { it.value to it.index + 1 }
        val scores = mutableMapOf<String, Double>()
        for (ids in listOf(semanticIds, keywordIds)) {
            ids.forEachIndexed { index, id ->
                scores[id] = scores.getOrDefault(id, 0.0) + 1.0 / (RRF_OFFSET + index + 1)
            }
        }
        return scores.keys.sortedWith(
            compareByDescending<String> { scores.getValue(it) }
                .thenBy { semanticRanks[it] ?: Int.MAX_VALUE }
                .thenBy { it },
        ).take(SupportProgramRankingFacade.MAX_CANDIDATES)
    }

    private fun tokenize(text: String): Set<String> =
        TOKEN.findAll(Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT))
            .map { it.value }.toSet()

    private companion object {
        const val RRF_OFFSET = 60.0
        val TOKEN = Regex("[a-z0-9가-힣]+")
    }
}
