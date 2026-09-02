package ai.govbiz.core._common.ai_config

import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.time.Duration
import java.util.function.Supplier
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient

class AiServiceClientPropertiesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1:8000",
            "https://ai-service.internal/",
            "http://ai-service.internal:1",
            "http://ai-service.internal:65535",
        ],
    )
    fun acceptsHttpUrisAndPositiveDurations(baseUrl: String) {
        val properties = AiServiceClientProperties(
            URI.create(baseUrl),
            CONNECT_TIMEOUT,
            READ_TIMEOUT,
        )

        assertEquals(URI.create(baseUrl), properties.baseUrl)
        assertEquals(CONNECT_TIMEOUT, properties.connectTimeout)
        assertEquals(READ_TIMEOUT, properties.readTimeout)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ftp://ai-service.internal:8000",
            "http:///internal/v1/health",
            "http://ai-service.internal:8000/base",
            "http://user:password@ai-service.internal:8000",
            "http://ai-service.internal:8000?debug=true",
            "http://ai-service.internal:8000#health",
            "http://ai-service.internal:0",
            "http://ai-service.internal:65536",
        ],
    )
    fun rejectsUnsupportedOrUnsafeBaseUris(baseUrl: String) {
        assertThrows(IllegalArgumentException::class.java) {
            AiServiceClientProperties(
                URI.create(baseUrl),
                CONNECT_TIMEOUT,
                READ_TIMEOUT,
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://ai-service.internal:0",
            "http://ai-service.internal:65536",
        ],
    )
    fun rejectsOutOfRangePortsWhenApplicationContextStarts(baseUrl: String) {
        ApplicationContextRunner()
            .withUserConfiguration(AiServiceClientConfig::class.java)
            .withBean(
                RestClient.Builder::class.java,
                Supplier { RestClient.builder() },
            )
            .withPropertyValues(
                "app.ai-service.base-url=$baseUrl",
                "app.ai-service.connect-timeout=1s",
                "app.ai-service.read-timeout=2s",
            )
            .run { context ->
                val startupFailure = context.startupFailure
                assertNotNull(startupFailure)

                val cause = rootCause(startupFailure!!)
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                assertTrue(cause.message.orEmpty().contains("port must be between 1 and 65535"))
            }
    }

    @ParameterizedTest
    @MethodSource("nonPositiveTimeouts")
    fun rejectsZeroOrNegativeTimeouts(connectTimeout: Duration, readTimeout: Duration) {
        assertThrows(IllegalArgumentException::class.java) {
            AiServiceClientProperties(
                URI.create("http://127.0.0.1:8000"),
                connectTimeout,
                readTimeout,
            )
        }
    }

    @Test
    fun rejectsMissingRequiredValues() {
        assertConstructorRejectsNull(null, CONNECT_TIMEOUT, READ_TIMEOUT)
        assertConstructorRejectsNull(
            URI.create("http://127.0.0.1:8000"),
            null,
            READ_TIMEOUT,
        )
        assertConstructorRejectsNull(
            URI.create("http://127.0.0.1:8000"),
            CONNECT_TIMEOUT,
            null,
        )
    }

    private fun assertConstructorRejectsNull(
        baseUrl: URI?,
        connectTimeout: Duration?,
        readTimeout: Duration?,
    ) {
        val constructor = AiServiceClientProperties::class.java.getDeclaredConstructor(
            URI::class.java,
            Duration::class.java,
            Duration::class.java,
        )
        val exception = assertThrows(InvocationTargetException::class.java) {
            constructor.newInstance(baseUrl, connectTimeout, readTimeout)
        }
        assertInstanceOf(
            NullPointerException::class.java,
            requireNotNull(exception.cause),
        )
    }

    private fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(2)

        @JvmStatic
        fun nonPositiveTimeouts(): Stream<Arguments> =
            Stream.of(
                Arguments.of(Duration.ZERO, READ_TIMEOUT),
                Arguments.of(Duration.ofMillis(-1), READ_TIMEOUT),
                Arguments.of(CONNECT_TIMEOUT, Duration.ZERO),
                Arguments.of(CONNECT_TIMEOUT, Duration.ofMillis(-1)),
            )
    }
}
