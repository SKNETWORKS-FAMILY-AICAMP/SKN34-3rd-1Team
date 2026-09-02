package ai.govbiz.core._common.exception

import ai.govbiz.core.supportprogram.service.search.SupportProgramSearchException
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(SupportProgramSearchException::class)
    fun handleSupportProgramSearchException(
        exception: SupportProgramSearchException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problemResponse(supportProgramDefinitionFor(exception.failure), request)

    @ExceptionHandler(AiServiceCallException::class)
    fun handleAiServiceCallException(
        exception: AiServiceCallException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problemResponse(definitionFor(exception.failure), request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = java.util.List.copyOf(
            exception.bindingResult.fieldErrors.map(::toValidationError),
        )

        return validationProblem(
            HttpStatus.BAD_REQUEST,
            URI.create("urn:govbiz:problem:request-validation-failed"),
            "Request Validation Failed",
            "One or more request fields are invalid.",
            "REQUEST_VALIDATION_FAILED",
            errors,
            request,
        )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleHandlerMethodValidationException(
        exception: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = java.util.List.copyOf(
            exception.parameterValidationResults
                .map { result ->
                    ValidationError(
                        result.methodParameter.parameterName ?: "request",
                        "INVALID_VALUE",
                    )
                }
                .distinct(),
        )

        return validationProblem(
            HttpStatus.BAD_REQUEST,
            URI.create("urn:govbiz:problem:request-validation-failed"),
            "Request Validation Failed",
            "One or more request fields are invalid.",
            "REQUEST_VALIDATION_FAILED",
            errors,
            request,
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        validationProblem(
            HttpStatus.BAD_REQUEST,
            URI.create("urn:govbiz:problem:request-validation-failed"),
            "Request Validation Failed",
            "The request body is invalid.",
            "REQUEST_VALIDATION_FAILED",
            emptyList(),
            request,
        )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleHttpMediaTypeNotSupportedException(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        validationProblem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            URI.create("urn:govbiz:problem:unsupported-media-type"),
            "Unsupported Media Type",
            "This endpoint accepts application/json requests.",
            "UNSUPPORTED_MEDIA_TYPE",
            emptyList(),
            request,
        )

    private fun validationProblem(
        status: HttpStatusCode,
        type: URI,
        title: String,
        detail: String,
        code: String,
        errors: List<ValidationError>,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val definition = ProblemDefinition(status, type, title, detail, code)
        return problemResponse(definition, request, errors)
    }

    private fun problemResponse(
        definition: ProblemDefinition,
        request: HttpServletRequest,
        errors: List<ValidationError>? = null,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(definition.status, definition.detail)
        problem.type = definition.type
        problem.title = definition.title
        problem.instance = URI.create(request.requestURI)
        problem.setProperty("code", definition.code)
        if (errors != null) {
            problem.setProperty("errors", errors)
        }

        return ResponseEntity.status(definition.status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem)
    }

    private fun toValidationError(fieldError: FieldError): ValidationError =
        ValidationError(fieldError.field, "INVALID_VALUE")

    private fun definitionFor(failure: AiServiceFailure): ProblemDefinition =
        when (failure) {
            AiServiceFailure.UPSTREAM_ERROR -> ProblemDefinition(
                HttpStatus.BAD_GATEWAY,
                URI.create("urn:govbiz:problem:ai-service-upstream-error"),
                "AI Service Upstream Error",
                "AI Service returned an unexpected HTTP status.",
                "AI_SERVICE_UPSTREAM_ERROR",
            )
            AiServiceFailure.INVALID_RESPONSE -> ProblemDefinition(
                HttpStatus.BAD_GATEWAY,
                URI.create("urn:govbiz:problem:ai-service-invalid-response"),
                "AI Service Invalid Response",
                "AI Service returned an invalid response.",
                "AI_SERVICE_INVALID_RESPONSE",
            )
            AiServiceFailure.UNAVAILABLE -> ProblemDefinition(
                HttpStatus.SERVICE_UNAVAILABLE,
                URI.create("urn:govbiz:problem:ai-service-unavailable"),
                "AI Service Unavailable",
                "AI Service is currently unavailable.",
                "AI_SERVICE_UNAVAILABLE",
            )
            AiServiceFailure.TIMEOUT -> ProblemDefinition(
                HttpStatus.GATEWAY_TIMEOUT,
                URI.create("urn:govbiz:problem:ai-service-timeout"),
                "AI Service Gateway Timeout",
                "AI Service did not respond within the configured timeout.",
                "AI_SERVICE_TIMEOUT",
            )
        }

    private fun supportProgramDefinitionFor(
        failure: SupportProgramSearchException.Failure,
    ): ProblemDefinition =
        when (failure) {
            SupportProgramSearchException.Failure.NOT_CONFIGURED -> ProblemDefinition(
                HttpStatus.SERVICE_UNAVAILABLE,
                URI.create("urn:govbiz:problem:support-program-source-not-configured"),
                "Support Program Search Unavailable",
                "The support program data source is not configured.",
                "SUPPORT_PROGRAM_SOURCE_NOT_CONFIGURED",
            )
            SupportProgramSearchException.Failure.UPSTREAM_ERROR -> ProblemDefinition(
                HttpStatus.BAD_GATEWAY,
                URI.create("urn:govbiz:problem:support-program-source-error"),
                "Support Program Source Error",
                "The support program data source returned an unexpected response.",
                "SUPPORT_PROGRAM_SOURCE_ERROR",
            )
            SupportProgramSearchException.Failure.INVALID_RESPONSE -> ProblemDefinition(
                HttpStatus.BAD_GATEWAY,
                URI.create("urn:govbiz:problem:support-program-invalid-response"),
                "Support Program Invalid Response",
                "The support program data source returned an invalid response.",
                "SUPPORT_PROGRAM_INVALID_RESPONSE",
            )
            SupportProgramSearchException.Failure.UNAVAILABLE -> ProblemDefinition(
                HttpStatus.SERVICE_UNAVAILABLE,
                URI.create("urn:govbiz:problem:support-program-source-unavailable"),
                "Support Program Source Unavailable",
                "The support program data source is currently unavailable.",
                "SUPPORT_PROGRAM_SOURCE_UNAVAILABLE",
            )
            SupportProgramSearchException.Failure.TIMEOUT -> ProblemDefinition(
                HttpStatus.GATEWAY_TIMEOUT,
                URI.create("urn:govbiz:problem:support-program-source-timeout"),
                "Support Program Source Timeout",
                "The support program data source did not respond in time.",
                "SUPPORT_PROGRAM_SOURCE_TIMEOUT",
            )
        }

    private data class ProblemDefinition(
        val status: HttpStatusCode,
        val type: URI,
        val title: String,
        val detail: String,
        val code: String,
    )

    private data class ValidationError(
        val field: String,
        val code: String,
    )
}
