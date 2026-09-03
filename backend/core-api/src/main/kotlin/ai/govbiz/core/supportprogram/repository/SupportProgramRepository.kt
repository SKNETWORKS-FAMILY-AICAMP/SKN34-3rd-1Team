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

/** 기업마당에서 정규화한 지원사업 카탈로그를 MySQL에 저장하고 읽습니다. */
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

    fun findByProgramId(programId: String): CatalogSupportProgram? =
        supportProgramMapper.findBySourceAndProgramId(BIZINFO_SOURCE_CODE, programId)
            ?.toCatalogProgram()

    private fun CatalogSupportProgram.toDbRow(): SupportProgramDbRow {
        val supportProgram = program

        return SupportProgramDbRow(
            sourceCode = BIZINFO_SOURCE_CODE,
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
                sourceName = BIZINFO_SOURCE_NAME,
                sourceUrl = sourceUrl,
                matchedReasons = emptyList(),
                recommendationScore = null,
            ),
            sortTimestamp = sourceSortTimestamp.orEmpty(),
        )
    }

    private fun readStringList(value: String): List<String> =
        java.util.List.copyOf(objectMapper.readValue(value, STRING_LIST_TYPE))

    private companion object {
        const val BIZINFO_SOURCE_CODE = "BIZINFO"
        const val BIZINFO_SOURCE_NAME = "기업마당"

        val STRING_LIST_TYPE = object : TypeReference<List<String>>() {}
    }
}
