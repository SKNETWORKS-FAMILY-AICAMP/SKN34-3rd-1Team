package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core._common.test.MySqlTestContainerConfig
import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramSourceDocument
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramContentHashHelper
import ai.govbiz.core.supportprogram.service.search.SupportProgramSearchService
import java.time.LocalDate
import java.time.LocalDateTime
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
        "app.bizinfo.sync.enabled=false",
        "app.support-program-index.enabled=false",
    ],
)
@Import(MySqlTestContainerConfig::class)
class SupportProgramRepositoryIntegrationTest {

    @Autowired
    private lateinit var repository: SupportProgramRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var supportProgramSearchService: SupportProgramSearchService

    @BeforeEach
    fun deleteSupportPrograms() {
        jdbcTemplate.update("DELETE FROM support_program_source_document")
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

        val stored = requireNotNull(repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_JSON"))

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

        assertEquals(
            catalogProgram,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_EMPTY"),
        )
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

        assertEquals(
            updated,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_UPSERT"),
        )
    }

    @Test
    fun preservesTheLatestSourceProgramIdCasingWhenUpsertingTheSameIdentity() {
        repository.synchronizeBizInfo(
            listOf(catalogProgram(id = "PBLN_CASE", title = "변경 전 공고")),
        )
        val updated = catalogProgram(id = "pbln_case", title = "변경 후 공고")

        repository.synchronizeBizInfo(listOf(updated))

        assertEquals(1, countRows("BIZINFO"))
        assertEquals(
            updated,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_CASE"),
        )
        val stored = requireNotNull(
            repository.findPresentBySourceAndProgramId("BIZINFO", "pbln_case"),
        )
        assertEquals("pbln_case", stored.program.id)
        assertEquals(
            "BIZINFO:pbln_case",
            SupportProgramIndexDocumentMapper.fromCatalog(stored).id,
        )
    }

    @Test
    fun returnsNullWhenProgramDoesNotExist() {
        assertNull(repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_NOT_FOUND"))
    }

    @Test
    fun storesSourceDocumentsByCompositeIdentityAndHidesThemWhenTheProgramIsInactive() {
        val program = catalogProgram(id = "PBLN_EVIDENCE", title = "근거 공고")
        repository.upsert(program)
        val content = "공식 원문입니다. 온라인으로 신청하고 문의는 수행기관에 합니다."
        val document = SupportProgramSourceDocument(
            sourceCode = "BIZINFO",
            sourceProgramId = "PBLN_EVIDENCE",
            sourceUrl = program.program.sourceUrl,
            content = content,
            contentHash = SupportProgramContentHashHelper.sha256(content),
            fetchedAt = LocalDateTime.of(2026, 9, 5, 12, 0),
        )

        repository.upsertSourceDocument(document)

        assertEquals(
            document,
            repository.findPresentSourceDocument("BIZINFO", "PBLN_EVIDENCE"),
        )

        repository.synchronizeBizInfo(emptyList())

        assertNull(repository.findPresentSourceDocument("BIZINFO", "PBLN_EVIDENCE"))
    }

    @Test
    fun replacesEverySourceDocumentValueWhenUpsertingTheSameCompositeIdentity() {
        val program = catalogProgram(id = "PBLN_EVIDENCE_UPSERT", title = "근거 갱신 공고")
        repository.upsert(program)
        val originalContent = "최초 공식 원문입니다. 온라인으로 신청합니다."
        val updatedContent = "갱신된 공식 원문입니다. 신청 방법과 제출 서류를 확인합니다."
        val original = SupportProgramSourceDocument(
            sourceCode = "BIZINFO",
            sourceProgramId = program.program.id,
            sourceUrl = program.program.sourceUrl,
            content = originalContent,
            contentHash = SupportProgramContentHashHelper.sha256(originalContent),
            fetchedAt = LocalDateTime.of(2026, 9, 5, 10, 0),
        )
        val updated = original.copy(
            content = updatedContent,
            contentHash = SupportProgramContentHashHelper.sha256(updatedContent),
            fetchedAt = LocalDateTime.of(2026, 9, 5, 12, 0),
        )

        repository.upsertSourceDocument(original)
        repository.upsertSourceDocument(updated)

        assertEquals(
            updated,
            repository.findPresentSourceDocument("BIZINFO", "PBLN_EVIDENCE_UPSERT"),
        )
        assertEquals(1, countSourceDocumentRows("BIZINFO", "PBLN_EVIDENCE_UPSERT"))
    }

    @Test
    fun separatesSourceDocumentsWhoseRawProgramIdsMatchAcrossSources() {
        val rawProgramId = "SHARED_EVIDENCE_ID"
        val bizInfoProgram = catalogProgram(id = rawProgramId, title = "기업마당 근거 공고")
        repository.upsert(bizInfoProgram)
        insertProgram(sourceCode = "OTHER", sourceProgramId = rawProgramId, title = "다른 제공처 근거 공고")
        val bizInfoContent = "기업마당 공식 원문입니다. 온라인 신청을 안내합니다."
        val otherContent = "다른 제공처 공식 원문입니다. 별도 신청 절차를 안내합니다."
        val bizInfoDocument = SupportProgramSourceDocument(
            sourceCode = "BIZINFO",
            sourceProgramId = rawProgramId,
            sourceUrl = bizInfoProgram.program.sourceUrl,
            content = bizInfoContent,
            contentHash = SupportProgramContentHashHelper.sha256(bizInfoContent),
            fetchedAt = LocalDateTime.of(2026, 9, 5, 12, 0),
        )
        val otherDocument = SupportProgramSourceDocument(
            sourceCode = "OTHER",
            sourceProgramId = rawProgramId,
            sourceUrl = "https://example.com/program/$rawProgramId",
            content = otherContent,
            contentHash = SupportProgramContentHashHelper.sha256(otherContent),
            fetchedAt = LocalDateTime.of(2026, 9, 5, 12, 0),
        )

        repository.upsertSourceDocument(bizInfoDocument)
        repository.upsertSourceDocument(otherDocument)

        assertEquals(bizInfoDocument, repository.findPresentSourceDocument("BIZINFO", rawProgramId))
        assertEquals(otherDocument, repository.findPresentSourceDocument("OTHER", rawProgramId))
        assertEquals(1, countSourceDocumentRows("BIZINFO", rawProgramId))
        assertEquals(1, countSourceDocumentRows("OTHER", rawProgramId))
    }

    @Test
    fun findsProgramsByTheCompleteSourceIdentityAndExcludesInactiveRows() {
        val bizInfoProgram = catalogProgram(id = "SHARED_ID", title = "기업마당 공고")
        repository.upsert(bizInfoProgram)
        insertProgram(sourceCode = "OTHER", sourceProgramId = "SHARED_ID", title = "다른 제공처 공고")

        assertEquals(
            bizInfoProgram,
            repository.findPresentBySourceAndProgramId("BIZINFO", "SHARED_ID"),
        )
        val otherProgram = requireNotNull(
            repository.findPresentBySourceAndProgramId("OTHER", "SHARED_ID"),
        )
        assertEquals("OTHER", otherProgram.program.sourceCode)
        assertEquals("OTHER", otherProgram.program.sourceName)
        assertEquals("다른 제공처 공고", otherProgram.program.title)

        repository.synchronizeBizInfo(emptyList())

        assertNull(repository.findPresentBySourceAndProgramId("BIZINFO", "SHARED_ID"))
        assertEquals(
            "다른 제공처 공고",
            repository.findPresentBySourceAndProgramId("OTHER", "SHARED_ID")?.program?.title,
        )
    }

    @Test
    fun findsPresentProgramsAcrossSourcesForSearch() {
        val current = catalogProgram(id = "PBLN_CURRENT", title = "현재 노출 공고")
        val missing = catalogProgram(id = "PBLN_MISSING", title = "사라진 공고")
        repository.synchronizeBizInfo(listOf(current, missing))
        repository.synchronizeBizInfo(listOf(current))
        insertProgram(sourceCode = "OTHER", sourceProgramId = "PBLN_OTHER", title = "다른 제공처 공고")

        assertEquals(
            listOf("BIZINFO:PBLN_CURRENT", "OTHER:PBLN_OTHER"),
            repository.findPresent().map { it.program.sourceQualifiedId },
        )
    }

    @Test
    fun excludesClosedAndUpcomingDatabaseProgramsWhenAcceptingOnly() {
        val open = catalogProgram(id = "PBLN_OPEN", title = "접수 중 공고")
        val closed = catalogProgram(
            id = "PBLN_CLOSED",
            title = "마감 공고",
            applicationPeriod = "2000-01-01 ~ 2000-01-31",
            applicationStartDate = LocalDate.of(2000, 1, 1),
            applicationEndDate = LocalDate.of(2000, 1, 31),
        )
        val upcoming = catalogProgram(
            id = "PBLN_UPCOMING",
            title = "예정 공고",
            applicationPeriod = "9999-01-01 ~ 9999-12-31",
            applicationStartDate = LocalDate.of(9999, 1, 1),
            applicationEndDate = LocalDate.of(9999, 12, 31),
        )
        repository.synchronizeBizInfo(listOf(open, closed, upcoming))

        val result = supportProgramSearchService.search("", acceptingOnly = true)

        assertEquals(listOf("PBLN_OPEN"), result.programs.map(SupportProgram::id))
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
        assertEquals(
            snapshot[0],
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_SYNC_A"),
        )
        assertEquals(
            snapshot[1],
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_SYNC_B"),
        )
    }

    @Test
    fun publishesOnlyTheMostRecentlyStartedBizInfoSyncGeneration() {
        val original = catalogProgram(id = "PBLN_ORIGINAL", title = "기존 공개 공고")
        val staleSnapshot = listOf(catalogProgram(id = "PBLN_STALE", title = "늦게 끝난 이전 수집"))
        val currentSnapshot = listOf(catalogProgram(id = "PBLN_CURRENT", title = "최신 수집 공고"))
        repository.synchronizeBizInfo(listOf(original))

        val staleGeneration = repository.startBizInfoSyncGeneration()
        val currentGeneration = repository.startBizInfoSyncGeneration()

        assertFalse(repository.publishBizInfoSnapshotIfCurrent(staleSnapshot, staleGeneration))
        assertEquals(
            original,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_ORIGINAL"),
        )

        assertTrue(repository.publishBizInfoSnapshotIfCurrent(currentSnapshot, currentGeneration))
        assertNull(repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_ORIGINAL"))
        assertEquals(
            currentSnapshot.single(),
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_CURRENT"),
        )
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
        assertNull(repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_MISSING"))
    }

    @Test
    fun marksAReappearingBizInfoProgramAsPresentAgain() {
        val remaining = catalogProgram(id = "PBLN_ALWAYS", title = "계속 제공되는 공고")
        val reappearing = catalogProgram(id = "PBLN_REAPPEARING", title = "재등장 공고")
        repository.synchronizeBizInfo(listOf(remaining, reappearing))
        repository.synchronizeBizInfo(listOf(remaining))

        repository.synchronizeBizInfo(listOf(remaining, reappearing))

        assertTrue(isSourcePresent("BIZINFO", "PBLN_REAPPEARING"))
        assertEquals(
            reappearing,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_REAPPEARING"),
        )
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
    fun rejectsAnotherSourceBeforeItCanReplaceTheBizInfoSnapshot() {
        val existing = catalogProgram(id = "PBLN_EXISTING", title = "기존 기업마당 공고")
        val otherSourceProgram = existing.copy(
            program = existing.program.copy(
                id = "OTHER_1",
                sourceCode = "OTHER",
                title = "잘못 섞인 다른 제공처 공고",
            ),
        )
        repository.synchronizeBizInfo(listOf(existing))

        assertThrows(IllegalArgumentException::class.java) {
            repository.synchronizeBizInfo(listOf(otherSourceProgram))
        }

        assertEquals(
            existing,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_EXISTING"),
        )
        assertEquals(0, countRows("OTHER"))
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

        assertEquals(
            original,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_ROLLBACK_A"),
        )
        assertEquals(
            shouldRemainPresent,
            repository.findPresentBySourceAndProgramId("BIZINFO", "PBLN_ROLLBACK_B"),
        )
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

    private fun countSourceDocumentRows(sourceCode: String, sourceProgramId: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM support_program_source_document
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
            sourceCode = "BIZINFO",
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
