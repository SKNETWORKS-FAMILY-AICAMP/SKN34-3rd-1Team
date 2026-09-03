package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core._common.test.MySqlTestContainerConfig
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(
    properties = [
        "app.ai-service.base-url=http://127.0.0.1:1",
        "app.ai-service.connect-timeout=10ms",
        "app.ai-service.read-timeout=10ms",
    ],
)
@Import(MySqlTestContainerConfig::class)
@Transactional
class SupportProgramRepositoryIntegrationTest {

    @Autowired
    private lateinit var repository: SupportProgramRepository

    @Test
    fun storesAndReadsKoreanArraysAndNullableDates() {
        val catalogProgram = catalogProgram(
            id = "PBLN_JSON",
            title = "서울 \"AI\" 기업 지원",
            categories = listOf("AI", "창업"),
            regions = listOf("서울", "전국"),
            applicationPeriod = "상시 접수",
            applicationStartDate = null,
            applicationEndDate = null,
        )

        repository.upsert(catalogProgram)

        val stored = requireNotNull(repository.findByProgramId("PBLN_JSON"))

        assertEquals(catalogProgram, stored)
        assertNull(stored.program.applicationStartDate)
        assertNull(stored.program.applicationEndDate)
    }

    @Test
    fun storesAndReadsEmptyArraysAndBlankSortTimestamp() {
        val catalogProgram = catalogProgram(
            id = "PBLN_EMPTY",
            title = "빈 분류 공고",
            categories = emptyList(),
            regions = emptyList(),
            applicationPeriod = "상시 접수",
            applicationStartDate = null,
            applicationEndDate = null,
        ).copy(sortTimestamp = "")

        repository.upsert(catalogProgram)

        assertEquals(catalogProgram, repository.findByProgramId("PBLN_EMPTY"))
    }

    @Test
    fun upsertsTheSameBizInfoIdWithoutCreatingAnotherRow() {
        repository.upsert(
            catalogProgram(
                id = "PBLN_UPSERT",
                title = "변경 전 공고",
                categories = listOf("AI"),
                regions = listOf("서울"),
                applicationPeriod = "2000-01-01 ~ 9999-12-31",
                applicationStartDate = LocalDate.of(2000, 1, 1),
                applicationEndDate = LocalDate.of(9999, 12, 31),
            ),
        )
        val updated = catalogProgram(
            id = "PBLN_UPSERT",
            title = "변경 후 공고",
            categories = listOf("AI", "수출"),
            regions = listOf("서울", "경기"),
            applicationPeriod = "2001-01-01 ~ 9999-12-31",
            applicationStartDate = LocalDate.of(2001, 1, 1),
            applicationEndDate = LocalDate.of(9999, 12, 31),
        )

        repository.upsert(updated)

        assertEquals(updated, repository.findByProgramId("PBLN_UPSERT"))
    }

    @Test
    fun returnsNullWhenProgramDoesNotExist() {
        assertNull(repository.findByProgramId("PBLN_NOT_FOUND"))
    }

    private fun catalogProgram(
        id: String,
        title: String,
        categories: List<String>,
        regions: List<String>,
        applicationPeriod: String,
        applicationStartDate: LocalDate?,
        applicationEndDate: LocalDate?,
    ) = CatalogSupportProgram(
        program = SupportProgram(
            id = id,
            title = title,
            organization = "수행기관",
            summary = "중소기업의 AI 기술 활용을 지원합니다.",
            categories = categories,
            regions = regions,
            targetDescription = "중소기업",
            applicationPeriod = applicationPeriod,
            applicationStartDate = applicationStartDate,
            applicationEndDate = applicationEndDate,
            status = SupportProgramStatus.OPEN,
            sourceName = "기업마당",
            sourceUrl = "https://www.bizinfo.go.kr/detail?id=$id",
            matchedReasons = emptyList(),
        ),
        sortTimestamp = "2026-08-21 14:19:54",
    )
}
