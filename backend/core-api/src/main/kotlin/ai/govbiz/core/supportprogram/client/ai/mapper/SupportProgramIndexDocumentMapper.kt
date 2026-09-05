package ai.govbiz.core.supportprogram.client.ai.mapper

import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramIndexDocumentRequest
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** 색인 생성과 검색이 동일한 공고 버전을 참조하도록 검색 문서를 구성합니다. */
object SupportProgramIndexDocumentMapper {
    const val SOURCE_CODE = "BIZINFO"
    const val MAX_DOCUMENTS = 20_000
    private const val MAX_TEXT_CODE_POINTS = 12_000
    private val UNSUPPORTED_CONTROL_TEXT = Regex("[\\p{C}&&[^\\n\\r\\t]]")

    fun fromBizInfo(candidate: CatalogSupportProgram): AiSupportProgramIndexDocumentRequest {
        val program = candidate.program
        val fullText = listOf(
            "제목: ${program.title}",
            "기관: ${program.organization}",
            "지원대상: ${program.targetDescription}",
            "분야: ${program.categories.joinToString(", ")}",
            "지역: ${program.regions.joinToString(", ")}",
            "신청기간: ${program.applicationPeriod}",
            "내용: ${program.summary}",
        ).joinToString("\n").replace(UNSUPPORTED_CONTROL_TEXT, " ")
        val text = if (fullText.codePointCount(0, fullText.length) > MAX_TEXT_CODE_POINTS) {
            fullText.substring(0, fullText.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS))
        } else fullText
        val contentHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)),
        )
        return AiSupportProgramIndexDocumentRequest("$SOURCE_CODE:${program.id}", contentHash, text)
    }
}
