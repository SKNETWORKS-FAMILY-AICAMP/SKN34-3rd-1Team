package ai.govbiz.core.supportprogram.client.ai.mapper

import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper.catalogProgram
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportProgramIndexDocumentMapperTest {
    @Test
    fun createsAStableSourceIdentityAndHashOfTheExactUtf8Text() {
        val candidate = catalogProgram("PBLN:한글")
        val document = SupportProgramIndexDocumentMapper.fromCatalog(candidate)
        assertEquals("BIZINFO:PBLN:한글", document.id)
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.text.toByteArray(StandardCharsets.UTF_8))),
            document.contentHash,
        )
        assertTrue(document.text.contains("제목: PBLN:한글 지원사업"))
        assertTrue(document.text.contains("분야: AI, 기술"))
        assertTrue(document.text.contains("지역: 서울"))
        assertEquals(document, SupportProgramIndexDocumentMapper.fromCatalog(candidate))
        assertNotEquals(
            document.contentHash,
            SupportProgramIndexDocumentMapper.fromCatalog(candidate.copy(program = candidate.program.copy(summary = "변경된 지원내용"))).contentHash,
        )
    }

    @Test
    fun distinguishesProgramsWithTheSameRawIdFromDifferentSources() {
        val bizInfo = catalogProgram("SHARED")
        val other = bizInfo.copy(
            program = bizInfo.program.copy(
                sourceCode = "OTHER",
                sourceName = "다른 제공처",
                sourceUrl = "https://other.example/program/SHARED",
            ),
        )

        val bizInfoDocument = SupportProgramIndexDocumentMapper.fromCatalog(bizInfo)
        val otherDocument = SupportProgramIndexDocumentMapper.fromCatalog(other)

        assertEquals("BIZINFO:SHARED", bizInfoDocument.id)
        assertEquals("OTHER:SHARED", otherDocument.id)
        assertNotEquals(bizInfoDocument.id, otherDocument.id)
    }

    @Test
    fun doesNotReembedWhenOnlyTodaysDerivedStatusOrSortTimestampChanges() {
        val candidate = catalogProgram("one")
        val changed = candidate.copy(program = candidate.program.copy(status = SupportProgramStatus.CLOSED), sortTimestamp = "newer")
        assertEquals(SupportProgramIndexDocumentMapper.fromCatalog(candidate), SupportProgramIndexDocumentMapper.fromCatalog(changed))
    }

    @Test
    fun normalizesUnsupportedControlAndFormatCharactersBeforeHashing() {
        val candidate = catalogProgram("one", "AI\u0000지원\u200b사업\n다음\t줄")
        val document = SupportProgramIndexDocumentMapper.fromCatalog(candidate)
        assertTrue(document.text.endsWith("내용: AI 지원 사업\n다음\t줄"))
        assertFalse(document.text.contains('\u0000'))
        assertFalse(document.text.contains('\u200b'))
    }

    @Test
    fun truncatesTextWithoutBreakingUnicodeSurrogatePairs() {
        val candidate = catalogProgram("one", "🙂".repeat(15_000))
        val document = SupportProgramIndexDocumentMapper.fromCatalog(candidate)
        assertEquals(12_000, document.text.codePointCount(0, document.text.length))
        assertFalse(document.text.last().isHighSurrogate())
        assertTrue(document.text.endsWith("🙂"))
    }
}
