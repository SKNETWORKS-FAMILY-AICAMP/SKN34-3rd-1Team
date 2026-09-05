package ai.govbiz.core.supportprogram.service.sync

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramIndexClient
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexBatchRequest
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramCatalogFingerprintHelper
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import org.springframework.stereotype.Service

/** 공개 전 공고 스냅샷의 벡터를 준비하고, 저장된 현재 공고의 누락 색인을 복구합니다. */
@Service
class SupportProgramIndexSyncService(
    private val repository: SupportProgramRepository,
    private val client: AiSupportProgramIndexClient,
) {
    /**
     * 이미 MySQL에 공개된 공고에서 누락된 벡터만 복구합니다.
     *
     * 이 작업은 삭제를 수행하지 않습니다. 새 카탈로그의 벡터를 준비하는 도중 이전 스냅샷 기준의
     * 정리 작업이 새 벡터를 삭제하지 않게 하기 위해서입니다.
     */
    fun repair(): Int {
        val programs = repository.findPresent()
        val status = repository.findBizInfoSyncStatus()
        val repairTarget = repairTargetFor(programs, status)
        val needsLegacyBootstrap =
            repairTarget == null &&
                status?.publishedCatalogFingerprint == null &&
                programs.any { it.program.sourceCode == BIZINFO_SOURCE_CODE }
        val indexedCount = try {
            indexSnapshot(programs)
        } catch (exception: RuntimeException) {
            try {
                repairTarget?.markNotReady()
            } catch (statusRecordingException: RuntimeException) {
                exception.addSuppressed(statusRecordingException)
            }
            throw exception
        }
        if (repairTarget != null) {
            repairTarget.markReady()
        } else if (needsLegacyBootstrap) {
            repository.bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(programs)
        }
        return indexedCount
    }

    /**
     * MySQL에 공개하기 전 카탈로그 스냅샷의 현재 버전을 색인합니다.
     *
     * 모든 batch가 성공할 때만 호출자에게 반환하므로, 호출자는 이 메서드가 성공한 뒤에만 해당
     * 스냅샷을 DB에 공개할 수 있습니다.
     */
    fun indexSnapshot(programs: List<CatalogSupportProgram>): Int {
        check(programs.size <= SupportProgramIndexDocumentMapper.MAX_DOCUMENTS) { "index catalog exceeds supported limit" }
        val documents = programs.map(SupportProgramIndexDocumentMapper::fromCatalog)
        check(documents.map { it.id }.toSet().size == documents.size) { "duplicate catalog identities" }
        // 외부 API는 DB transaction 밖에서 호출하며, 같은 문서 버전은 AI Service가 재사용합니다.
        for (batch in documents.chunked(BATCH_SIZE)) {
            val result = client.indexBatch(AiSupportProgramIndexBatchRequest(batch))
            if (result.indexedCount != batch.size) {
                throw AiServiceCallException.invalidResponse("AI Service did not acknowledge every index document", null)
            }
        }
        return documents.size
    }

    private fun repairTargetFor(
        programs: List<CatalogSupportProgram>,
        status: SupportProgramSyncStatus?,
    ): BizInfoRepairTarget? {
        status ?: return null
        val publishedGeneration = status.publishedGeneration ?: return null
        val publishedFingerprint = status.publishedCatalogFingerprint ?: return null
        val bizInfoPrograms = programs.filter { it.program.sourceCode == BIZINFO_SOURCE_CODE }
        if (bizInfoPrograms.size != status.publishedProgramCount) return null
        if (SupportProgramCatalogFingerprintHelper.calculate(bizInfoPrograms) != publishedFingerprint) return null

        return BizInfoRepairTarget(
            publishedGeneration = publishedGeneration,
            catalogFingerprint = publishedFingerprint,
            programCount = bizInfoPrograms.size,
        )
    }

    private inner class BizInfoRepairTarget(
        private val publishedGeneration: Long,
        private val catalogFingerprint: String,
        private val programCount: Int,
    ) {
        fun markReady() {
            repository.markBizInfoIndexReadyIfPublishedSnapshotMatches(
                publishedGeneration = publishedGeneration,
                expectedCatalogFingerprint = catalogFingerprint,
                expectedProgramCount = programCount,
            )
        }

        fun markNotReady() {
            repository.markBizInfoIndexNotReadyIfPublishedSnapshotMatches(
                publishedGeneration = publishedGeneration,
                expectedCatalogFingerprint = catalogFingerprint,
                expectedProgramCount = programCount,
            )
        }
    }

    private companion object {
        const val BIZINFO_SOURCE_CODE = "BIZINFO"
        const val BATCH_SIZE = 16
    }
}
