package ai.govbiz.core.supportprogram.controller

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.ApiExceptionHandler
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.facade.SupportProgramRankingFacade
import ai.govbiz.core.supportprogram.facade.AiSupportProgramRetrievalFacade
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.detail.SupportProgramDetailService
import ai.govbiz.core.supportprogram.service.search.SupportProgramSearchService
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
    private lateinit var supportProgramRepository: SupportProgramRepository

    @Mock
    private lateinit var retrieval: AiSupportProgramRetrievalFacade

    private lateinit var ranking: StubSupportProgramRankingFacade

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        ranking = StubSupportProgramRankingFacade()
        val service = SupportProgramSearchService(
            supportProgramRepository,
            ranking,
            retrieval,
        )
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                SupportProgramController(
                    searchService = service,
                    detailService = SupportProgramDetailService(supportProgramRepository),
                ),
            )
            .setControllerAdvice(ApiExceptionHandler())
            .build()
    }

    @Test
    fun returnsTheStableFrontendContractIncludingNullableParsedDates() {
        Mockito.doReturn(listOf(catalogProgram())).`when`(retrieval)
            .retrieve("서울 AI", listOf(catalogProgram()))
        Mockito.doReturn(listOf(catalogProgram()))
            .`when`(supportProgramRepository)
            .findPresent()
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
            .andExpect(jsonPath("$.programs[0].sourceCode").value("BIZINFO"))
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

    @Test
    fun returnsAnEmptyListWhenTheCurrentCatalogIsEmpty() {
        Mockito.doReturn(emptyList<CatalogSupportProgram>())
            .`when`(supportProgramRepository)
            .findPresent()

        mockMvc.perform(get(PATH).queryParam("query", "서울"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.query").value("서울"))
            .andExpect(jsonPath("$.programs").isEmpty())
    }

    @Test
    fun returnsTheCurrentProgramDetailsBySourceAndOriginalId() {
        Mockito.doReturn(catalogProgram()).`when`(supportProgramRepository)
            .findPresentBySourceAndProgramId("BIZINFO", "PBLN_TEST")

        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceCode", "BIZINFO")
                .queryParam("sourceProgramId", "PBLN_TEST"),
        )
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value("PBLN_TEST"))
            .andExpect(jsonPath("$.sourceCode").value("BIZINFO"))
            .andExpect(jsonPath("$.title").value("서울 AI 지원사업"))
            .andExpect(jsonPath("$.matchedReasons").isEmpty())
            .andExpect(jsonPath("$.recommendationScore").value(nullValue()))
    }

    @Test
    fun returnsAStableNotFoundProblemForMissingOrInactiveProgramDetails() {
        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceCode", "BIZINFO")
                .queryParam("sourceProgramId", "PBLN_MISSING"),
        )
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:govbiz:problem:support-program-not-found"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Support Program Not Found"))
            .andExpect(
                jsonPath("$.detail")
                    .value("The requested support program does not exist or is no longer available."),
            )
            .andExpect(jsonPath("$.code").value("SUPPORT_PROGRAM_NOT_FOUND"))
            .andExpect(jsonPath("$.instance").value(DETAIL_PATH))
    }

    @Test
    fun validatesBothCompositeDetailIdentityParameters() {
        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceCode", " ")
                .queryParam("sourceProgramId", "PBLN_TEST"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))

        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceCode", "other:source")
                .queryParam("sourceProgramId", "PBLN_TEST"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
    }

    @Test
    fun returnsTheValidationProblemWhenACompositeDetailIdentityParameterIsMissing() {
        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceProgramId", "PBLN_TEST"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:govbiz:problem:request-validation-failed"))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[0].field").value("sourceCode"))
            .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"))
    }

    @Test
    fun rejectsDetailIdentityValuesThatExceedTheirPublicLimits() {
        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceCode", "S".repeat(65))
                .queryParam("sourceProgramId", "PBLN_TEST"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))

        mockMvc.perform(
            get(DETAIL_PATH)
                .queryParam("sourceCode", "BIZINFO")
                .queryParam("sourceProgramId", "P".repeat(256)),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
    }

    @ParameterizedTest
    @MethodSource("aiServiceProblemCases")
    fun mapsEveryDirectAiClientFailureToAStableProblem(problemCase: ProblemCase) {
        Mockito.doReturn(listOf(catalogProgram())).`when`(retrieval)
            .retrieve("서울", listOf(catalogProgram()))
        Mockito.doReturn(listOf(catalogProgram()))
            .`when`(supportProgramRepository)
            .findPresent()
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
    fun mapsIncompleteSemanticIndexToServiceUnavailable() {
        Mockito.doReturn(listOf(catalogProgram())).`when`(supportProgramRepository).findPresent()
        Mockito.doThrow(AiServiceCallException.unavailable(null)).`when`(retrieval)
            .retrieve("서울", listOf(catalogProgram()))

        mockMvc.perform(get(PATH).queryParam("query", "서울"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"))
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

    private fun catalogProgram() = CatalogSupportProgram(
        program = SupportProgram(
            id = "PBLN_TEST",
            sourceCode = "BIZINFO",
            title = "서울 AI 지원사업",
            organization = "수행기관",
            summary = "AI 기술 지원",
            categories = listOf("AI"),
            regions = listOf("서울"),
            targetDescription = "중소기업",
            applicationPeriod = "상시 접수",
            applicationStartDate = null,
            applicationEndDate = null,
            status = SupportProgramStatus.OPEN,
            sourceName = "기업마당",
            sourceUrl = "https://www.bizinfo.go.kr/detail?id=PBLN_TEST",
            matchedReasons = emptyList(),
            recommendationScore = null,
        ),
        sortTimestamp = "2026-08-21 10:00:00",
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
        const val DETAIL_PATH = "/api/v1/support-programs/detail"
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
