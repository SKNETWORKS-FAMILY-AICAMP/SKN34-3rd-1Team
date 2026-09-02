package ai.govbiz.core.supportprogram.client.bizinfo.mapper

import ai.govbiz.core.supportprogram.client.bizinfo.dto.BizInfoProgramPayload
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import java.net.URI
import java.net.URISyntaxException
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.regex.Pattern
import org.springframework.web.util.HtmlUtils

/** 기업마당 응답을 검증된 검색 후보로 변환하는 순수 매퍼입니다. */
internal object BizInfoProgramMapper {
    private val ISO_DATE: Pattern = Pattern.compile("\\d{4}[-./]\\d{2}[-./]\\d{2}")
    private val HTML_BLOCK: Pattern = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val HTML_BREAK: Pattern = Pattern.compile("(?i)<br\\s*/?>|</p>|</li>")
    private val HTML_TAG: Pattern = Pattern.compile("(?s)<[^>]*>")
    private val CATEGORY_SEPARATOR: Pattern = Pattern.compile("[,/·>]")
    private val WHITESPACE: Pattern = Pattern.compile("\\s+")

    private val SOURCE_REGION_ALIASES = mapOf(
        "서울" to "서울", "서울특별시" to "서울",
        "부산" to "부산", "부산광역시" to "부산",
        "대구" to "대구", "대구광역시" to "대구",
        "인천" to "인천", "인천광역시" to "인천",
        "광주" to "광주", "광주광역시" to "광주",
        "대전" to "대전", "대전광역시" to "대전",
        "울산" to "울산", "울산광역시" to "울산",
        "세종" to "세종", "세종특별자치시" to "세종",
        "경기" to "경기", "경기도" to "경기",
        "강원" to "강원", "강원특별자치도" to "강원",
        "충북" to "충북", "충청북도" to "충북",
        "충남" to "충남", "충청남도" to "충남",
        "전북" to "전북", "전북특별자치도" to "전북",
        "전남" to "전남", "전라남도" to "전남",
        "경북" to "경북", "경상북도" to "경북",
        "경남" to "경남", "경상남도" to "경남",
        "제주" to "제주", "제주특별자치도" to "제주",
        "전국" to "전국",
    )

    fun mapAndDeduplicate(
        payloads: List<BizInfoProgramPayload?>,
        today: LocalDate,
    ): List<CatalogSupportProgram> {
        val programs = LinkedHashMap<String, CatalogSupportProgram>()
        for (payload in payloads) {
            val program = toCatalogProgram(payload, today) ?: continue
            programs.putIfAbsent(program.program.id, program)
        }
        return java.util.List.copyOf(programs.values)
    }

    private fun toCatalogProgram(
        payload: BizInfoProgramPayload?,
        today: LocalDate,
    ): CatalogSupportProgram? {
        val id = payload?.id?.takeUnless { it.isBlank() } ?: return null
        val title = payload.title?.takeUnless { it.isBlank() } ?: return null
        val sourceUrl = officialSourceUrl(payload.sourceUrl) ?: return null
        val applicationPeriod = firstPresent(payload.applicationPeriod, "정보 없음")
        val dates = parseDates(applicationPeriod)
        val summary = plainText(payload.summaryHtml)
        val organization = firstPresent(
            payload.executingOrganization,
            payload.jurisdictionOrganization,
            "정보 없음",
        )

        return CatalogSupportProgram(
            program = SupportProgram(
                id = id.trim(),
                title = title.trim(),
                organization = organization,
                summary = if (summary.isBlank()) "정보 없음" else summary,
                categories = categories(payload.category),
                regions = regions(payload.hashtags),
                targetDescription = firstPresent(payload.target, "정보 없음"),
                applicationPeriod = applicationPeriod,
                applicationStartDate = dates.start,
                applicationEndDate = dates.end,
                status = determineStatus(applicationPeriod, dates, today),
                sourceName = "기업마당",
                sourceUrl = sourceUrl,
                matchedReasons = emptyList(),
            ),
            sortTimestamp = firstPresent(payload.updatedAt, payload.createdAt, ""),
        )
    }

    private fun parseDates(applicationPeriod: String): DateRange {
        val dates = ArrayList<LocalDate>(2)
        val matcher = ISO_DATE.matcher(applicationPeriod)
        while (matcher.find() && dates.size < 2) {
            try {
                dates += LocalDate.parse(
                    matcher.group().replace('.', '-').replace('/', '-'),
                )
            } catch (_: DateTimeParseException) {
                // 잘못된 외부 날짜는 원문 기간에 그대로 남기고 추정하지 않습니다.
            }
        }
        if (dates.size >= 2) return DateRange(dates[0], dates[1])
        if (dates.size == 1) {
            val normalized = normalize(applicationPeriod)
            if ("까지" in normalized && !isRollingPeriod(normalized)) {
                return DateRange(null, dates[0])
            }
            if ("부터" in normalized || isRollingPeriod(normalized)) {
                return DateRange(dates[0], null)
            }
        }
        return DateRange(null, null)
    }

    private fun determineStatus(
        applicationPeriod: String,
        dates: DateRange,
        today: LocalDate,
    ): SupportProgramStatus {
        if (dates.start != null && today.isBefore(dates.start)) {
            return SupportProgramStatus.UPCOMING
        }
        if (dates.end != null && today.isAfter(dates.end)) {
            return SupportProgramStatus.CLOSED
        }
        if (dates.start != null && dates.end != null) return SupportProgramStatus.OPEN

        val normalized = normalize(applicationPeriod)
        if (containsAny(normalized, "추후 공지", "추후공지", "접수 예정", "접수예정")) {
            return SupportProgramStatus.UPCOMING
        }
        if (isRollingPeriod(normalized)) return SupportProgramStatus.OPEN
        if (dates.end != null) return SupportProgramStatus.OPEN
        if (containsAny(normalized, "접수 종료", "접수종료", "모집 종료", "모집종료", "마감 완료")) {
            return SupportProgramStatus.CLOSED
        }
        return SupportProgramStatus.UNKNOWN
    }

    private fun isRollingPeriod(value: String): Boolean = containsAny(
        value,
        "예산 소진", "예산소진", "상시", "선착순", "모집 완료시", "모집완료시",
        "모집 마감시", "모집마감시", "수시", "정원 마감", "정원마감",
        "규모 마감", "규모마감", "소진시", "완료시",
    )

    private fun containsAny(value: String, vararg candidates: String): Boolean =
        candidates.any(value::contains)

    private fun categories(category: String?): List<String> {
        val value = category ?: return emptyList()
        if (value.isBlank()) return emptyList()
        return CATEGORY_SEPARATOR.split(value)
            .asSequence()
            .map { it.trim() }
            .filter { !it.isBlank() }
            .distinct()
            .toList()
            .let { java.util.List.copyOf(it) }
    }

    private fun regions(hashtags: String?): List<String> {
        val value = hashtags ?: return emptyList()
        if (value.isBlank()) return emptyList()

        val regions = LinkedHashSet<String>()
        for (hashtag in value.split(',')) {
            val normalized = hashtag.trim()
            if (normalized == "전남광주") {
                regions += "광주"
                regions += "전남"
                continue
            }
            SOURCE_REGION_ALIASES[normalized]?.let(regions::add)
        }
        if ("전국" in regions || regions.size >= 10) return listOf("전국")
        return java.util.List.copyOf(regions)
    }

    private fun plainText(html: String?): String {
        val value = html ?: return ""
        if (value.isBlank()) return ""
        val withoutBlocks = HTML_BLOCK.matcher(value).replaceAll(" ")
        val withBreaks = HTML_BREAK.matcher(withoutBlocks).replaceAll(" ")
        val withoutTags = HTML_TAG.matcher(withBreaks).replaceAll(" ")
        return WHITESPACE.matcher(
            HtmlUtils.htmlUnescape(withoutTags).replace('\u00a0', ' '),
        ).replaceAll(" ").trim()
    }

    private fun officialSourceUrl(value: String?): String? {
        val source = value ?: return null
        if (source.isBlank()) return null
        return try {
            val uri = URI(source.trim())
            val host = uri.host
            val supportedScheme = uri.scheme.equals("https", ignoreCase = true) ||
                uri.scheme.equals("http", ignoreCase = true)
            val officialHost = host != null &&
                (host.equals("bizinfo.go.kr", ignoreCase = true) ||
                    host.lowercase(Locale.ROOT).endsWith(".bizinfo.go.kr"))
            if (supportedScheme && officialHost) uri.toString() else null
        } catch (_: URISyntaxException) {
            null
        }
    }

    private fun firstPresent(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim()
    }

    private data class DateRange(val start: LocalDate?, val end: LocalDate?)
}
