package ai.govbiz.core.supportprogram.service.evaluation

import ai.govbiz.core.supportprogram.client.ai.mapper.SupportProgramIndexDocumentMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper.catalogProgram
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.evaluation.config.SupportProgramSearchEvaluationFixtureExportProperties
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import tools.jackson.databind.json.JsonMapper

@ExtendWith(MockitoExtension::class)
class SupportProgramSearchEvaluationFixtureExportCommandLineRunnerTest {

    @TempDir
    private lateinit var directory: Path

    @Mock
    private lateinit var supportProgramRepository: SupportProgramRepository

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun exportsOnlyOpenProgramsAsASortedLabelReadyCatalogFixture() {
        val laterCanonicalId = catalogProgram("PBLN_Z", summary = "나중 공고")
            .copy(sortTimestamp = "2026-09-05 10:00:00")
        val earlierCanonicalId = catalogProgram("PBLN_A", summary = "먼저 공고")
            .copy(sortTimestamp = "2026-09-04 10:00:00")
        val closed = catalogProgram("PBLN_CLOSED")
            .copy(program = catalogProgram("PBLN_CLOSED").program.copy(status = SupportProgramStatus.CLOSED))
        val upcoming = catalogProgram("PBLN_UPCOMING")
            .copy(program = catalogProgram("PBLN_UPCOMING").program.copy(status = SupportProgramStatus.UPCOMING))
        doReturn(listOf(laterCanonicalId, closed, earlierCanonicalId, upcoming))
            .`when`(supportProgramRepository)
            .findPresentBizInfo()

        val output = directory.resolve("fixture.json")
        Files.writeString(output, "previous-fixture", UTF_8)

        runner("bizinfo-20260905-v1", output).run()

        val fixture = objectMapper.readTree(Files.readAllBytes(output))
        assertEquals(
            setOf("name", "dataType", "catalog", "docs", "cases"),
            fixture.propertyNames().asSequence().toSet(),
        )
        assertEquals("bizinfo-20260905-v1", fixture.get("name").stringValue())
        assertEquals("real_catalog_snapshot_unlabeled", fixture.get("dataType").stringValue())
        assertEquals(4, fixture.get("catalog").get("presentProgramCount").intValue())
        assertEquals(2, fixture.get("catalog").get("eligibleProgramCount").intValue())
        assertEquals(
            catalogFingerprint(listOf(earlierCanonicalId, laterCanonicalId)),
            fixture.get("catalog").get("eligibleCatalogFingerprint").stringValue(),
        )
        assertEquals(0, fixture.get("cases").size())

        val expectedPrograms = listOf(earlierCanonicalId, laterCanonicalId)
        assertEquals(2, fixture.get("docs").size())
        expectedPrograms.forEachIndexed { index, program ->
            val expectedDocument = SupportProgramIndexDocumentMapper.fromBizInfo(program)
            val document = fixture.get("docs").get(index)
            assertEquals(
                setOf("id", "contentHash", "text", "sortTimestamp"),
                document.propertyNames().asSequence().toSet(),
            )
            assertEquals(expectedDocument.id, document.get("id").stringValue())
            assertEquals(expectedDocument.contentHash, document.get("contentHash").stringValue())
            assertEquals(expectedDocument.text, document.get("text").stringValue())
            assertEquals(program.sortTimestamp, document.get("sortTimestamp").stringValue())
        }
        verify(supportProgramRepository).findPresentBizInfo()
    }

    @Test
    fun keepsThePreviousFixtureWhenThereIsNoOpenProgramToLabel() {
        val closed = catalogProgram("PBLN_CLOSED")
            .copy(program = catalogProgram("PBLN_CLOSED").program.copy(status = SupportProgramStatus.CLOSED))
        val upcoming = catalogProgram("PBLN_UPCOMING")
            .copy(program = catalogProgram("PBLN_UPCOMING").program.copy(status = SupportProgramStatus.UPCOMING))
        doReturn(listOf(closed, upcoming)).`when`(supportProgramRepository).findPresentBizInfo()
        val output = directory.resolve("fixture.json")
        Files.writeString(output, "previous-fixture", UTF_8)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            runner("bizinfo-20260905-v1", output).run()
        }

        assertTrue(exception.message.orEmpty().contains("eligible"))
        assertEquals("previous-fixture", Files.readString(output, UTF_8))
        verify(supportProgramRepository).findPresentBizInfo()
    }

    @Test
    fun keepsThePreviousFixtureWhenCatalogLoadingFails() {
        val output = directory.resolve("fixture.json")
        Files.writeString(output, "previous-fixture", UTF_8)
        doThrow(IllegalStateException("database unavailable"))
            .`when`(supportProgramRepository)
            .findPresentBizInfo()

        val exception = assertThrows(IllegalStateException::class.java) {
            runner("bizinfo-20260905-v1", output).run()
        }

        assertEquals("database unavailable", exception.message)
        assertEquals("previous-fixture", Files.readString(output, UTF_8))
        verify(supportProgramRepository).findPresentBizInfo()
    }

    @Test
    fun keepsThePreviousFixtureWhenAnEligibleProgramHasNoStableSortTimestamp() {
        val withoutSortTimestamp = catalogProgram("PBLN_OPEN")
            .copy(sortTimestamp = "")
        doReturn(listOf(withoutSortTimestamp)).`when`(supportProgramRepository).findPresentBizInfo()
        val output = directory.resolve("fixture.json")
        Files.writeString(output, "previous-fixture", UTF_8)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            runner("bizinfo-20260905-v1", output).run()
        }

        assertTrue(exception.message.orEmpty().contains("sortTimestamp"))
        assertEquals("previous-fixture", Files.readString(output, UTF_8))
        verify(supportProgramRepository).findPresentBizInfo()
    }

    @Test
    fun rejectsAnEmptyOrSurroundingWhitespaceFixtureName() {
        val output = directory.resolve("fixture.json")

        for (invalidName in listOf("", " ", " bizinfo-v1", "bizinfo-v1 ")) {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                SupportProgramSearchEvaluationFixtureExportProperties(invalidName, output)
            }
            assertTrue(exception.message.orEmpty().contains("name"))
        }

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SupportProgramSearchEvaluationFixtureExportProperties("bizinfo-v1", Path.of(""))
        }
        assertTrue(exception.message.orEmpty().contains("output"))
    }

    private fun runner(name: String, output: Path) = SupportProgramSearchEvaluationFixtureExportCommandLineRunner(
        properties = SupportProgramSearchEvaluationFixtureExportProperties(name, output),
        supportProgramRepository = supportProgramRepository,
        objectMapper = objectMapper,
    )

    private fun catalogFingerprint(programs: List<CatalogSupportProgram>): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                programs
                    .map(SupportProgramIndexDocumentMapper::fromBizInfo)
                    .map { document -> "${document.id}:${document.contentHash}" }
                    .sorted()
                    .joinToString("\n")
                    .toByteArray(UTF_8),
            ),
        )
}
