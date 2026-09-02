package ai.govbiz.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "app.ai-service.base-url=http://127.0.0.1:1",
        "app.ai-service.connect-timeout=10ms",
        "app.ai-service.read-timeout=10ms",
    ],
)
class CoreApiApplicationTest {

    @Test
    fun contextLoadsWithoutRunningFastApi() {
    }
}
