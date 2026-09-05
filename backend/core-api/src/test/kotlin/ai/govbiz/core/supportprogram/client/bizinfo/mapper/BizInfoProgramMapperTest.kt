package ai.govbiz.core.supportprogram.client.bizinfo.mapper

import ai.govbiz.core.supportprogram.client.bizinfo.dto.BizInfoProgramPayload
import ai.govbiz.core.supportprogram.client.bizinfo.exception.BizInfoClientException
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BizInfoProgramMapperTest {

    @Test
    fun mapsDistinctValidProgramsInResponseOrder() {
        val programs = BizInfoProgramMapper.mapValidated(
            payloads = listOf(
                payload(id = "PBLN_1"),
                payload(id = "PBLN_2", title = "부산 수출 사업"),
            ),
            today = TODAY,
        )

        assertEquals(listOf("PBLN_1", "PBLN_2"), programs.map { it.program.id })
        assertEquals(listOf("BIZINFO", "BIZINFO"), programs.map { it.program.sourceCode })
        assertEquals(listOf("서울 AI 사업", "부산 수출 사업"), programs.map { it.program.title })
    }

    @Test
    fun cleansHtmlAndDerivesDatesAndHonestStatuses() {
        val programs = BizInfoProgramMapper.mapValidated(
            payloads = listOf(
                payload(
                    id = "open",
                    summaryHtml = "<p>AI &amp; 기술<br>지원</p>",
                    applicationPeriod = "2026-08-20 ~ 2026-09-11",
                ),
                payload(id = "rolling", applicationPeriod = "2026-08-01 ~ 예산 소진시까지"),
                payload(id = "upcoming", applicationPeriod = "추후 공지"),
                payload(id = "unknown", applicationPeriod = "세부사업별 상이"),
                payload(id = "closed", applicationPeriod = "2026-07-01 ~ 2026-07-31"),
            ),
            today = TODAY,
        )
        val byId = programs.associateBy { it.program.id }

        assertEquals("AI & 기술 지원", byId.getValue("open").program.summary)
        assertFalse(byId.getValue("open").program.summary.contains("<"))
        assertEquals(LocalDate.of(2026, 8, 20), byId.getValue("open").program.applicationStartDate)
        assertEquals(LocalDate.of(2026, 9, 11), byId.getValue("open").program.applicationEndDate)
        assertEquals(SupportProgramStatus.OPEN, byId.getValue("open").program.status)
        assertEquals(SupportProgramStatus.OPEN, byId.getValue("rolling").program.status)
        assertNull(byId.getValue("rolling").program.applicationEndDate)
        assertEquals(SupportProgramStatus.UPCOMING, byId.getValue("upcoming").program.status)
        assertEquals(SupportProgramStatus.UNKNOWN, byId.getValue("unknown").program.status)
        assertEquals(SupportProgramStatus.CLOSED, byId.getValue("closed").program.status)
    }

    @Test
    fun rejectsNullOrMissingRequiredProgramValues() {
        val invalidPayloads = listOf(
            null,
            payload(id = " "),
            payload(title = null),
            payload(sourceUrl = " "),
        )

        invalidPayloads.forEach { invalidPayload ->
            assertInvalidResponse(listOf(invalidPayload))
        }
    }

    @Test
    fun rejectsNonOfficialSourceUrls() {
        val invalidUrls = listOf(
            "https://bizinfo.go.kr.example.com/detail",
            "https://example.com/detail",
            "ftp://www.bizinfo.go.kr/detail",
            "not a url",
        )

        invalidUrls.forEach { invalidUrl ->
            assertInvalidResponse(listOf(payload(sourceUrl = invalidUrl)))
        }
    }

    @Test
    fun rejectsDuplicateProgramIdsUsingTheDatabaseIdentityRules() {
        assertInvalidResponse(
            listOf(
                payload(id = "PBLN_1"),
                payload(id = " pbln_1 ", title = "중복 공고"),
            ),
        )
    }

    private fun assertInvalidResponse(payloads: List<BizInfoProgramPayload?>) {
        val exception = assertThrows(BizInfoClientException::class.java) {
            BizInfoProgramMapper.mapValidated(payloads, TODAY)
        }

        assertEquals(BizInfoClientException.Failure.INVALID_RESPONSE, exception.failure)
    }

    private fun payload(
        id: String? = "PBLN_1",
        title: String? = "서울 AI 사업",
        sourceUrl: String? = "https://www.bizinfo.go.kr/detail?id=PBLN_1",
        summaryHtml: String? = "<p>AI 기술 지원</p>",
        applicationPeriod: String? = "2026-08-01 ~ 2026-09-30",
    ) = BizInfoProgramPayload(
        title = title,
        sourceUrl = sourceUrl,
        id = id,
        jurisdictionOrganization = "주관기관",
        executingOrganization = "수행기관",
        summaryHtml = summaryHtml,
        category = "기술/AI",
        createdAt = "2026-08-01",
        applicationPeriod = applicationPeriod,
        updatedAt = "2026-08-02",
        target = "중소기업",
        hashtags = "서울,AI",
        applicationMethod = "온라인",
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 1)
    }
}
