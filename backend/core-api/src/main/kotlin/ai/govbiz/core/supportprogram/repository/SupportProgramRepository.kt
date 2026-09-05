package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramSourceDocument
import ai.govbiz.core.supportprogram.domain.SupportProgramStatusResolver
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncOutcome
import ai.govbiz.core.supportprogram.domain.SupportProgramSyncStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramCatalogFingerprintHelper
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramDbRow
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramMapper
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramSourceDocumentDbRow
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramSyncStatusDbRow
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/** 외부 제공처에서 정규화한 지원사업 카탈로그를 MySQL에 저장하고 읽습니다. */
@Repository
class SupportProgramRepository(
    private val supportProgramMapper: SupportProgramMapper,
    private val objectMapper: ObjectMapper,
    @param:Qualifier("seoulClock") private val clock: Clock,
) {

    @Transactional
    fun upsert(catalogProgram: CatalogSupportProgram) {
        supportProgramMapper.upsert(catalogProgram.toDbRow())
    }

    /** 기업마당의 완전한 최신 목록으로 기존 기업마당 데이터의 노출 상태와 내용을 원자적으로 갱신합니다. */
    @Transactional
    fun synchronizeBizInfo(programs: List<CatalogSupportProgram>) {
        requireBizInfoPrograms(programs)
        replaceBizInfoSnapshot(programs)
    }

    /**
     * 새 동기화 실행에 단조 증가하는 세대를 부여합니다.
     *
     * 외부 API와 AI 호출은 이 짧은 DB transaction 뒤에서 수행합니다. 이후 먼저 시작한 실행이 늦게
     * 끝나더라도 최신 세대만 카탈로그를 공개하게 합니다.
     */
    @Transactional
    fun startBizInfoSyncGeneration(): Long {
        supportProgramMapper.insertSyncGenerationIfAbsent(BIZINFO_SOURCE_CODE)
        val currentGeneration = requireNotNull(
            supportProgramMapper.lockLatestStartedGeneration(BIZINFO_SOURCE_CODE),
        ) { "BizInfo sync generation row was not created" }
        check(currentGeneration < Long.MAX_VALUE) { "BizInfo sync generation overflow" }
        val nextGeneration = currentGeneration + 1
        check(
            supportProgramMapper.updateLatestStartedGeneration(BIZINFO_SOURCE_CODE, nextGeneration) == 1,
        ) { "BizInfo sync generation was not updated" }
        return nextGeneration
    }

    /**
     * 현재 실행 세대가 가장 최근에 시작된 경우에만 완성된 공고 스냅샷을 공개합니다.
     *
     * 행 잠금과 카탈로그 교체를 같은 짧은 transaction으로 묶어, 늦게 끝난 이전 수집 결과가
     * 더 최신 실행의 결과를 덮어쓰지 못하게 합니다.
     */
    @Transactional
    fun publishBizInfoSnapshotIfCurrent(
        programs: List<CatalogSupportProgram>,
        generation: Long,
    ): Boolean {
        requireBizInfoPrograms(programs)
        val latestGeneration = requireNotNull(
            supportProgramMapper.lockLatestStartedGeneration(BIZINFO_SOURCE_CODE),
        ) { "BizInfo sync generation row does not exist" }
        if (latestGeneration != generation) return false

        replaceBizInfoSnapshot(programs)
        supportProgramMapper.upsertSyncSuccess(
            sourceCode = BIZINFO_SOURCE_CODE,
            generation = generation,
            catalogFingerprint = SupportProgramCatalogFingerprintHelper.calculate(programs),
            programCount = programs.size,
            occurredAt = LocalDateTime.now(clock),
        )
        return true
    }

    /** 색인 복구가 읽은 공개 스냅샷과 상태 행이 아직 같을 때만 준비 완료를 기록합니다. */
    @Transactional
    fun markBizInfoIndexReadyIfPublishedSnapshotMatches(
        publishedGeneration: Long,
        expectedCatalogFingerprint: String,
        expectedProgramCount: Int,
    ): Boolean =
        supportProgramMapper.markSyncIndexReady(
            sourceCode = BIZINFO_SOURCE_CODE,
            publishedGeneration = publishedGeneration,
            catalogFingerprint = expectedCatalogFingerprint,
            programCount = expectedProgramCount,
        ) == 1

    /** 색인 복구가 읽은 공개 스냅샷과 상태 행이 아직 같을 때만 준비 실패를 기록합니다. */
    @Transactional
    fun markBizInfoIndexNotReadyIfPublishedSnapshotMatches(
        publishedGeneration: Long,
        expectedCatalogFingerprint: String,
        expectedProgramCount: Int,
    ): Boolean =
        supportProgramMapper.markSyncIndexNotReady(
            sourceCode = BIZINFO_SOURCE_CODE,
            publishedGeneration = publishedGeneration,
            catalogFingerprint = expectedCatalogFingerprint,
            programCount = expectedProgramCount,
        ) == 1

    /**
     * V4 상태 행이 없던 기존 공개 공고를 전체 색인 복구가 성공한 뒤에만 신뢰 가능한 준비 상태로 채택합니다.
     *
     * 실제 공개 세대를 복원할 수 없으므로 sentinel 세대 0을 사용합니다. 이미 지문이 있는 새 스냅샷은
     * SQL의 조건부 갱신으로 절대 바꾸지 않습니다.
     */
    @Transactional
    fun bootstrapBizInfoLegacySnapshotAfterSuccessfulRepair(programs: List<CatalogSupportProgram>): Boolean {
        val bizInfoPrograms = programs.filter { it.program.sourceCode == BIZINFO_SOURCE_CODE }
        if (bizInfoPrograms.isEmpty()) return false

        supportProgramMapper.insertSyncStatusIfAbsent(BIZINFO_SOURCE_CODE)
        return supportProgramMapper.bootstrapSyncStatusIfUntrusted(
            sourceCode = BIZINFO_SOURCE_CODE,
            catalogFingerprint = SupportProgramCatalogFingerprintHelper.calculate(bizInfoPrograms),
            programCount = bizInfoPrograms.size,
        ) > 0
    }

    /** 현재 세대의 수집 또는 필수 색인 실패만 기록하고, 이전 공개 카탈로그는 변경하지 않습니다. */
    @Transactional
    fun recordBizInfoSyncFailureIfCurrent(generation: Long): Boolean {
        val latestGeneration = requireNotNull(
            supportProgramMapper.lockLatestStartedGeneration(BIZINFO_SOURCE_CODE),
        ) { "BizInfo sync generation row does not exist" }
        if (latestGeneration != generation) return false

        supportProgramMapper.upsertSyncFailure(
            sourceCode = BIZINFO_SOURCE_CODE,
            occurredAt = LocalDateTime.now(clock),
        )
        return true
    }

    /** 현재 노출 중인 공고를 제공처 코드와 제공처 원본 ID 조합으로 조회합니다. */
    fun findPresentBySourceAndProgramId(
        sourceCode: String,
        sourceProgramId: String,
    ): CatalogSupportProgram? =
        supportProgramMapper.findBySourceAndProgramId(sourceCode, sourceProgramId)
            ?.toCatalogProgram()

    /** 현재 모든 제공처 스냅샷에 포함된 공고를 검색 후보로 반환합니다. */
    fun findPresent(): List<CatalogSupportProgram> =
        java.util.List.copyOf(
            supportProgramMapper
                .findPresent()
                .map { it.toCatalogProgram() },
        )

    /** 기업마당 동기화가 한 번도 성공·실패하지 않았으면 null을 반환합니다. */
    fun findBizInfoSyncStatus(): SupportProgramSyncStatus? =
        supportProgramMapper.findSyncStatus(BIZINFO_SOURCE_CODE)?.toSyncStatus()

    /** 현재 공개된 공고에 연결된 공식 원문 근거 문서를 조회합니다. */
    fun findPresentSourceDocument(
        sourceCode: String,
        sourceProgramId: String,
    ): SupportProgramSourceDocument? =
        supportProgramMapper
            .findPresentSourceDocument(sourceCode, sourceProgramId)
            ?.toSourceDocument()

    /** 외부 호출을 끝낸 뒤 짧은 DB transaction으로 공식 원문 근거 문서를 저장합니다. */
    @Transactional
    fun upsertSourceDocument(document: SupportProgramSourceDocument) {
        supportProgramMapper.upsertSourceDocument(document.toDbRow())
    }

    private fun CatalogSupportProgram.toDbRow(): SupportProgramDbRow {
        val supportProgram = program

        return SupportProgramDbRow(
            sourceCode = supportProgram.sourceCode,
            sourceProgramId = supportProgram.id,
            title = supportProgram.title,
            organization = supportProgram.organization,
            summary = supportProgram.summary,
            categoriesJson = objectMapper.writeValueAsString(supportProgram.categories),
            regionsJson = objectMapper.writeValueAsString(supportProgram.regions),
            targetDescription = supportProgram.targetDescription,
            applicationPeriodRaw = supportProgram.applicationPeriod,
            applicationStartDate = supportProgram.applicationStartDate,
            applicationEndDate = supportProgram.applicationEndDate,
            sourceUrl = supportProgram.sourceUrl,
            sourceSortTimestamp = sortTimestamp.takeIf(String::isNotBlank),
        )
    }

    private fun SupportProgramDbRow.toCatalogProgram(): CatalogSupportProgram {
        val period = applicationPeriodRaw
        val startDate = applicationStartDate
        val endDate = applicationEndDate

        return CatalogSupportProgram(
            program = SupportProgram(
                id = sourceProgramId,
                sourceCode = sourceCode,
                title = title,
                organization = organization,
                summary = summary,
                categories = readStringList(categoriesJson),
                regions = readStringList(regionsJson),
                targetDescription = targetDescription,
                applicationPeriod = period,
                applicationStartDate = startDate,
                applicationEndDate = endDate,
                status = SupportProgramStatusResolver.resolve(
                    applicationPeriod = period,
                    applicationStartDate = startDate,
                    applicationEndDate = endDate,
                    today = LocalDate.now(clock),
                ),
                sourceName = sourceNameFor(sourceCode),
                sourceUrl = sourceUrl,
                matchedReasons = emptyList(),
                recommendationScore = null,
            ),
            sortTimestamp = sourceSortTimestamp.orEmpty(),
        )
    }

    private fun SupportProgramSourceDocument.toDbRow(): SupportProgramSourceDocumentDbRow =
        SupportProgramSourceDocumentDbRow(
            sourceCode = sourceCode,
            sourceProgramId = sourceProgramId,
            sourceUrl = sourceUrl,
            content = content,
            contentHash = contentHash,
            fetchedAt = fetchedAt,
        )

    private fun SupportProgramSourceDocumentDbRow.toSourceDocument(): SupportProgramSourceDocument =
        SupportProgramSourceDocument(
            sourceCode = sourceCode,
            sourceProgramId = sourceProgramId,
            sourceUrl = sourceUrl,
            content = content,
            contentHash = contentHash,
            fetchedAt = requireNotNull(fetchedAt) { "source document fetchedAt must not be null" },
        )

    private fun SupportProgramSyncStatusDbRow.toSyncStatus(): SupportProgramSyncStatus =
        SupportProgramSyncStatus(
            sourceCode = sourceCode,
            publishedGeneration = publishedGeneration,
            publishedCatalogFingerprint = publishedCatalogFingerprint,
            publishedProgramCount = publishedProgramCount,
            indexReady = indexReady,
            lastSuccessfulSyncAt = lastSuccessfulSyncAt,
            lastFailedSyncAt = lastFailedSyncAt,
            lastSyncOutcome = SupportProgramSyncOutcome.valueOf(lastSyncOutcome),
        )

    private fun readStringList(value: String): List<String> =
        java.util.List.copyOf(objectMapper.readValue(value, STRING_LIST_TYPE))

    private fun sourceNameFor(sourceCode: String): String =
        if (sourceCode == BIZINFO_SOURCE_CODE) BIZINFO_SOURCE_NAME else sourceCode

    private fun requireBizInfoPrograms(programs: List<CatalogSupportProgram>) {
        require(programs.all { it.program.sourceCode == BIZINFO_SOURCE_CODE }) {
            "BizInfo synchronization accepts only BIZINFO source programs"
        }
    }

    private fun replaceBizInfoSnapshot(programs: List<CatalogSupportProgram>) {
        supportProgramMapper.markAllNotPresentBySourceCode(BIZINFO_SOURCE_CODE)
        programs.forEach { program ->
            supportProgramMapper.upsert(program.toDbRow())
        }
    }

    private companion object {
        const val BIZINFO_SOURCE_CODE = "BIZINFO"
        const val BIZINFO_SOURCE_NAME = "기업마당"

        val STRING_LIST_TYPE = object : TypeReference<List<String>>() {}
    }
}
