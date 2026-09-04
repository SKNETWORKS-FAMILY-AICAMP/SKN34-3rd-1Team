package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core._common.test.MySqlTestContainerConfig
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    properties = [
        "app.ai-service.base-url=http://127.0.0.1:1",
        "app.ai-service.connect-timeout=10ms",
        "app.ai-service.read-timeout=10ms",
    ],
)
@Import(MySqlTestContainerConfig::class)
class SupportProgramRepositoryIntegrationTest {

    @Autowired
    private lateinit var repository: SupportProgramRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun deleteSupportPrograms() {
        jdbcTemplate.update("DELETE FROM support_program")
    }

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

    @Test
    fun synchronizesTheSameSnapshotTwiceWithoutCreatingDuplicates() {
        val snapshot = listOf(
            catalogProgram(id = "PBLN_SYNC_A", title = "동일 공고 A"),
            catalogProgram(id = "PBLN_SYNC_B", title = "동일 공고 B"),
        )

        repository.synchronizeBizInfo(snapshot)
        repository.synchronizeBizInfo(snapshot)

        assertEquals(2, countRows("BIZINFO"))
        assertEquals(2, countPresentRows("BIZINFO"))
        assertEquals(snapshot[0], repository.findByProgramId("PBLN_SYNC_A"))
        assertEquals(snapshot[1], repository.findByProgramId("PBLN_SYNC_B"))
    }

    @Test
    fun marksOnlyMissingBizInfoProgramsAsNotPresent() {
        val remaining = catalogProgram(id = "PBLN_REMAINING", title = "계속 제공되는 공고")
        val missing = catalogProgram(id = "PBLN_MISSING", title = "사라진 공고")
        repository.synchronizeBizInfo(listOf(remaining, missing))

        repository.synchronizeBizInfo(listOf(remaining))

        assertEquals(2, countRows("BIZINFO"))
        assertTrue(isSourcePresent("BIZINFO", "PBLN_REMAINING"))
        assertFalse(isSourcePresent("BIZINFO", "PBLN_MISSING"))
        assertNull(repository.findByProgramId("PBLN_MISSING"))
    }

    @Test
    fun marksAReappearingBizInfoProgramAsPresentAgain() {
        val remaining = catalogProgram(id = "PBLN_ALWAYS", title = "계속 제공되는 공고")
        val reappearing = catalogProgram(id = "PBLN_REAPPEARING", title = "재등장 공고")
        repository.synchronizeBizInfo(listOf(remaining, reappearing))
        repository.synchronizeBizInfo(listOf(remaining))

        repository.synchronizeBizInfo(listOf(remaining, reappearing))

        assertTrue(isSourcePresent("BIZINFO", "PBLN_REAPPEARING"))
        assertEquals(reappearing, repository.findByProgramId("PBLN_REAPPEARING"))
    }

    @Test
    fun doesNotChangeProgramsFromAnotherSource() {
        insertProgram(sourceCode = "OTHER", sourceProgramId = "SHARED_ID", title = "다른 제공처 공고")
        repository.synchronizeBizInfo(
            listOf(catalogProgram(id = "SHARED_ID", title = "기업마당 공고")),
        )

        repository.synchronizeBizInfo(emptyList())

        assertTrue(isSourcePresent("OTHER", "SHARED_ID"))
        assertFalse(isSourcePresent("BIZINFO", "SHARED_ID"))
        assertEquals(1, countRows("OTHER"))
    }

    @Test
    fun rollsBackTheWholeSnapshotWhenAnUpsertFails() {
        val original = catalogProgram(id = "PBLN_ROLLBACK_A", title = "변경 전 공고")
        val shouldRemainPresent = catalogProgram(id = "PBLN_ROLLBACK_B", title = "유지되어야 하는 공고")
        repository.synchronizeBizInfo(listOf(original, shouldRemainPresent))
        val changed = original.copy(program = original.program.copy(title = "롤백되어야 하는 변경"))
        val invalid = catalogProgram(
            id = "PBLN_TOO_LONG",
            title = "가".repeat(501),
        )

        assertThrows(DataAccessException::class.java) {
            repository.synchronizeBizInfo(listOf(changed, invalid))
        }

        assertEquals(original, repository.findByProgramId("PBLN_ROLLBACK_A"))
        assertEquals(shouldRemainPresent, repository.findByProgramId("PBLN_ROLLBACK_B"))
        assertEquals(2, countPresentRows("BIZINFO"))
        assertEquals(0, countRowsByProgramId("BIZINFO", "PBLN_TOO_LONG"))
    }

    private fun countRows(sourceCode: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM support_program WHERE source_code = ?",
                Int::class.java,
                sourceCode,
            ),
        )

    private fun countPresentRows(sourceCode: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM support_program
                WHERE source_code = ?
                  AND is_source_present = TRUE
                """.trimIndent(),
                Int::class.java,
                sourceCode,
            ),
        )

    private fun countRowsByProgramId(sourceCode: String, sourceProgramId: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM support_program
                WHERE source_code = ?
                  AND source_program_id = ?
                """.trimIndent(),
                Int::class.java,
                sourceCode,
                sourceProgramId,
            ),
        )

    private fun isSourcePresent(sourceCode: String, sourceProgramId: String): Boolean =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT is_source_present
                FROM support_program
                WHERE source_code = ?
                  AND source_program_id = ?
                """.trimIndent(),
                Boolean::class.java,
                sourceCode,
                sourceProgramId,
            ),
        )

    private fun insertProgram(sourceCode: String, sourceProgramId: String, title: String) {
        jdbcTemplate.update(
            """
            INSERT INTO support_program (
                source_code,
                source_program_id,
                title,
                organization,
                summary,
                categories,
                regions,
                target_description,
                application_period_raw,
                source_url
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?)
            """.trimIndent(),
            sourceCode,
            sourceProgramId,
            title,
            "다른 수행기관",
            "다른 제공처의 공고입니다.",
            "[]",
            "[]",
            "중소기업",
            "상시 접수",
            "https://example.com/program/$sourceProgramId",
        )
    }

    private fun catalogProgram(
        id: String,
        title: String,
        categories: List<String> = listOf("AI"),
        regions: List<String> = listOf("서울"),
        applicationPeriod: String = "2000-01-01 ~ 9999-12-31",
        applicationStartDate: LocalDate? = LocalDate.of(2000, 1, 1),
        applicationEndDate: LocalDate? = LocalDate.of(9999, 12, 31),
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
