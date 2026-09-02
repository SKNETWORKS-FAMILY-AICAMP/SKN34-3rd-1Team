package ai.govbiz.core.supportprogram.controller

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.ApiExceptionHandler
import ai.govbiz.core.supportprogram.client.bizinfo.BizInfoClient
import ai.govbiz.core.supportprogram.client.bizinfo.dto.BizInfoProgramPayload
import ai.govbiz.core.supportprogram.client.bizinfo.exception.BizInfoClientException
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.facade.BizInfoSupportProgramCatalogFacade
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.service.search.SupportProgramSearchService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.stream.Stream
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@ExtendWith(MockitoExtension::class)
class SupportProgramControllerTest {

    @Mock
    private lateinit var client: BizInfoClient

    private lateinit var ranking: StubSupportProgramRankingFacade

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val clock = Clock.fixed(
            Instant.parse("2026-08-24T03:00:00Z"),
            ZoneId.of("Asia/Seoul"),
        )
        ranking = StubSupportProgramRankingFacade()
        val service = SupportProgramSearchService(
            BizInfoSupportProgramCatalogFacade(client, clock),
            ranking,
        )
        mockMvc = MockMvcBuilders
            .standaloneSetup(SupportProgramController(service))
            .setControllerAdvice(ApiExceptionHandler())
            .build()
    }

    @Test
    fun returnsTheStableFrontendContractIncludingNullableParsedDates() {
        Mockito.doReturn(listOf(payload("상시 접수")))
            .`when`(client)
            .fetchAll()
        ranking.response = { candidates ->
            listOf(
                candidates.single().program.copy(
                    recommendationScore = 96,
                    matchedReasons = listOf("서울 AI 기업 대상"),
                ),
            )
        }

        mockMvc.perform(get(PATH).queryParam("query", "  서울 AI  "))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.query").value("서울 AI"))
            .andExpect(jsonPath("$.programs[0].id").value("PBLN_TEST"))
            .andExpect(jsonPath("$.programs[0].status").value("OPEN"))
            .andExpect(jsonPath("$.programs[0].applicationPeriod").value("상시 접수"))
            .andExpect(jsonPath("$.programs[0].applicationStartDate").value(nullValue()))
            .andExpect(jsonPath("$.programs[0].applicationEndDate").value(nullValue()))
            .andExpect(jsonPath("$.programs[0].sourceName").value("기업마당"))
            .andExpect(jsonPath("$.programs[0].recommendationScore").value(96))
            .andExpect(jsonPath("$.programs[0].matchedReasons[0]").value("서울 AI 기업 대상"))
            .andExpect(
                jsonPath("$.programs[0].sourceUrl")
                    .value("https://www.bizinfo.go.kr/detail?id=PBLN_TEST"),
            )
    }

    @ParameterizedTest
    @MethodSource("supportProgramProblemCases")
    fun mapsEverySupportProgramFailureToAStableProblem(problemCase: ProblemCase) {
        Mockito.doThrow(problemCase.exception).`when`(client).fetchAll()

        assertProblem(
            mockMvc.perform(get(PATH).queryParam("query", "서울")),
            problemCase,
        )
    }

    @ParameterizedTest
    @MethodSource("aiServiceProblemCases")
    fun mapsEveryDirectAiClientFailureToAStableProblem(problemCase: ProblemCase) {
        Mockito.doReturn(listOf(payload("상시 접수"))).`when`(client).fetchAll()
        ranking.failure = problemCase.exception

        assertProblem(
            mockMvc.perform(get(PATH).queryParam("query", "서울")),
            problemCase,
        )
    }

    @Test
    fun requiresASearchQueryParameter() {
        mockMvc.perform(get(PATH))
            .andExpect(status().isBadRequest())
    }

    @Test
    fun rejectsAQueryLongerThanThePublicContractLimit() {
        mockMvc.perform(get(PATH).queryParam("query", "가".repeat(501)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:govbiz:problem:request-validation-failed"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.title").value("Request Validation Failed"))
            .andExpect(jsonPath("$.detail").value("One or more request fields are invalid."))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.instance").value(PATH))
            .andExpect(jsonPath("$.errors[0].field").value("query"))
            .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"))
    }

    private fun assertProblem(result: ResultActions, problemCase: ProblemCase) {
        result
            .andExpect(status().`is`(problemCase.status))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value(problemCase.type))
            .andExpect(jsonPath("$.status").value(problemCase.status))
            .andExpect(jsonPath("$.title").value(problemCase.title))
            .andExpect(jsonPath("$.detail").value(problemCase.detail))
            .andExpect(jsonPath("$.code").value(problemCase.code))
            .andExpect(jsonPath("$.instance").value(PATH))
            .andExpect(content().string(not(containsString(PRIVATE_DETAIL))))
    }

    private fun payload(period: String) =
        BizInfoProgramPayload(
            "서울 AI 지원사업",
            "https://www.bizinfo.go.kr/detail?id=PBLN_TEST",
            "PBLN_TEST",
            "중소벤처기업부",
            "수행기관",
            "<p>AI &amp; 기술 지원</p>",
            "AI",
            "2026-08-20 10:00:00",
            period,
            "2026-08-21 10:00:00",
            "중소기업",
            "AI,서울",
            "온라인",
        )

    private class StubSupportProgramRankingFacade : SupportProgramRankingFacade {
        var response: (List<CatalogSupportProgram>) -> List<SupportProgram> = { emptyList() }
        var failure: RuntimeException? = null

        override fun rank(
            query: String,
            candidates: List<CatalogSupportProgram>,
            limit: Int,
        ): List<SupportProgram> {
            failure?.let { throw it }
            return response(candidates)
        }
    }

    private companion object {
        const val PATH = "/api/v1/support-programs/search"
        const val PRIVATE_DETAIL = "private upstream detail"

        @JvmStatic
        fun aiServiceProblemCases(): Stream<ProblemCase> =
            Stream.of(
                ProblemCase(
                    AiServiceCallException.upstreamError(
                        PRIVATE_DETAIL,
                        IllegalStateException(PRIVATE_DETAIL),
                    ),
                    502,
                    "urn:govbiz:problem:ai-service-upstream-error",
                    "AI Service Upstream Error",
                    "AI Service returned an unexpected HTTP status.",
                    "AI_SERVICE_UPSTREAM_ERROR",
                ),
                ProblemCase(
                    AiServiceCallException.invalidResponse(
                        PRIVATE_DETAIL,
                        IllegalArgumentException(PRIVATE_DETAIL),
                    ),
                    502,
                    "urn:govbiz:problem:ai-service-invalid-response",
                    "AI Service Invalid Response",
                    "AI Service returned an invalid response.",
                    "AI_SERVICE_INVALID_RESPONSE",
                ),
                ProblemCase(
                    AiServiceCallException.unavailable(
                        IllegalStateException(PRIVATE_DETAIL),
                    ),
                    503,
                    "urn:govbiz:problem:ai-service-unavailable",
                    "AI Service Unavailable",
                    "AI Service is currently unavailable.",
                    "AI_SERVICE_UNAVAILABLE",
                ),
                ProblemCase(
                    AiServiceCallException.timeout(
                        IllegalStateException(PRIVATE_DETAIL),
                    ),
                    504,
                    "urn:govbiz:problem:ai-service-timeout",
                    "AI Service Gateway Timeout",
                    "AI Service did not respond within the configured timeout.",
                    "AI_SERVICE_TIMEOUT",
                ),
            )

        @JvmStatic
        fun supportProgramProblemCases(): Stream<ProblemCase> =
            Stream.of(
                ProblemCase(
                    BizInfoClientException.notConfigured(),
                    503,
                    "urn:govbiz:problem:support-program-source-not-configured",
                    "Support Program Search Unavailable",
                    "The support program data source is not configured.",
                    "SUPPORT_PROGRAM_SOURCE_NOT_CONFIGURED",
                ),
                ProblemCase(
                    BizInfoClientException.upstreamError(
                        PRIVATE_DETAIL,
                        IllegalStateException(PRIVATE_DETAIL),
                    ),
                    502,
                    "urn:govbiz:problem:support-program-source-error",
                    "Support Program Source Error",
                    "The support program data source returned an unexpected response.",
                    "SUPPORT_PROGRAM_SOURCE_ERROR",
                ),
                ProblemCase(
                    BizInfoClientException.invalidResponse(
                        PRIVATE_DETAIL,
                        IllegalArgumentException(PRIVATE_DETAIL),
                    ),
                    502,
                    "urn:govbiz:problem:support-program-invalid-response",
                    "Support Program Invalid Response",
                    "The support program data source returned an invalid response.",
                    "SUPPORT_PROGRAM_INVALID_RESPONSE",
                ),
                ProblemCase(
                    BizInfoClientException.unavailable(IllegalStateException(PRIVATE_DETAIL)),
                    503,
                    "urn:govbiz:problem:support-program-source-unavailable",
                    "Support Program Source Unavailable",
                    "The support program data source is currently unavailable.",
                    "SUPPORT_PROGRAM_SOURCE_UNAVAILABLE",
                ),
                ProblemCase(
                    BizInfoClientException.timeout(IllegalStateException(PRIVATE_DETAIL)),
                    504,
                    "urn:govbiz:problem:support-program-source-timeout",
                    "Support Program Source Timeout",
                    "The support program data source did not respond in time.",
                    "SUPPORT_PROGRAM_SOURCE_TIMEOUT",
                ),
            )
    }

    data class ProblemCase(
        val exception: RuntimeException,
        val status: Int,
        val type: String,
        val title: String,
        val detail: String,
        val code: String,
    )
}
