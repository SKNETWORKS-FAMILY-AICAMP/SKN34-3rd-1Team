package ai.govbiz.core.supportprogram.client.bizinfo.mapper

import ai.govbiz.core.supportprogram.client.bizinfo.dto.BizInfoProgramPayload
import ai.govbiz.core.supportprogram.client.bizinfo.exception.BizInfoClientException
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
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
        assertEquals(listOf("서울 AI 사업", "부산 수출 사업"), programs.map { it.program.title })
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
    ) = BizInfoProgramPayload(
        title = title,
        sourceUrl = sourceUrl,
        id = id,
        jurisdictionOrganization = "주관기관",
        executingOrganization = "수행기관",
        summaryHtml = "<p>AI 기술 지원</p>",
        category = "기술/AI",
        createdAt = "2026-08-01",
        applicationPeriod = "2026-08-01 ~ 2026-09-30",
        updatedAt = "2026-08-02",
        target = "중소기업",
        hashtags = "서울,AI",
        applicationMethod = "온라인",
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 1)
    }
}
