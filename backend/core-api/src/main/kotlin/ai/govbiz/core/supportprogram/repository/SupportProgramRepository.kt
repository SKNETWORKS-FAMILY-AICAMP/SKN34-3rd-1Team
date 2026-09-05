package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatusResolver
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramDbRow
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramMapper
import java.time.Clock
import java.time.LocalDate
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
