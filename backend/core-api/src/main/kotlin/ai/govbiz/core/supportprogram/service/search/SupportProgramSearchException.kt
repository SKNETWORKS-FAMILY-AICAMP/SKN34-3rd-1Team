package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.facade.exception.SupportProgramCatalogFacadeException

class SupportProgramSearchException private constructor(
    val failure: Failure,
    message: String?,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    enum class Failure {
        NOT_CONFIGURED,
        UPSTREAM_ERROR,
        INVALID_RESPONSE,
        UNAVAILABLE,
        TIMEOUT,
    }

    companion object {
        internal fun fromFacade(
            exception: SupportProgramCatalogFacadeException,
        ): SupportProgramSearchException =
            SupportProgramSearchException(
                failure = when (exception.failure) {
                    SupportProgramCatalogFacadeException.Failure.NOT_CONFIGURED ->
                        Failure.NOT_CONFIGURED
                    SupportProgramCatalogFacadeException.Failure.UPSTREAM_ERROR ->
                        Failure.UPSTREAM_ERROR
                    SupportProgramCatalogFacadeException.Failure.INVALID_RESPONSE ->
                        Failure.INVALID_RESPONSE
                    SupportProgramCatalogFacadeException.Failure.UNAVAILABLE ->
                        Failure.UNAVAILABLE
                    SupportProgramCatalogFacadeException.Failure.TIMEOUT ->
                        Failure.TIMEOUT
                },
                message = exception.message,
                cause = exception,
            )
    }
}
