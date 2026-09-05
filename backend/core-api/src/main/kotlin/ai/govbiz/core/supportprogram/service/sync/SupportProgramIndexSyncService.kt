package ai.govbiz.core.supportprogram.service.sync

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexBatchRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexPruneRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import org.springframework.stereotype.Service

/** 현재 MySQL 공고를 벡터 색인에 반영한 뒤 성공한 스냅샷에 한해서 이전 버전을 정리합니다. */
@Service
class SupportProgramIndexSyncService(
    private val repository: SupportProgramRepository,
    private val client: AiSupportProgramIndexClient,
) {
    fun sync(): Int {
        val programs = repository.findPresentBizInfo()
        check(programs.size <= SupportProgramIndexDocumentMapper.MAX_DOCUMENTS) { "index catalog exceeds supported limit" }
        val documents = programs.map(SupportProgramIndexDocumentMapper::fromBizInfo)
        check(documents.map { it.id }.toSet().size == documents.size) { "duplicate catalog identities" }
        // 외부 API는 DB transaction 밖에서 호출하며 재실행 시 이미 저장된 문서 버전은 재사용됩니다.
        for (batch in documents.chunked(BATCH_SIZE)) {
            val result = client.indexBatch(AiSupportProgramIndexBatchRequest(batch))
            if (result.indexedCount != batch.size) {
                throw AiServiceCallException.invalidResponse("AI Service did not acknowledge every index document", null)
            }
        }
        val result = client.prune(
            AiSupportProgramIndexPruneRequest(
                SupportProgramIndexDocumentMapper.SOURCE_CODE,
                documents.map { it.reference() },
            ),
        )
        if (result.retainedCount != documents.size) {
            throw AiServiceCallException.invalidResponse("AI Service returned an invalid retained index count", null)
        }
        return documents.size
    }

    private companion object {
        const val BATCH_SIZE = 16
    }
}
