package ai.govbiz.core._common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 브라우저 공개 API의 개발 환경 CORS 정책입니다. */
@Configuration(proxyBeanMethods = false)
class WebCorsConfig(
    @param:Value("\${app.cors.allowed-origin}") private val allowedOrigin: String,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigin)
            .allowedMethods("GET", "POST")
            .allowedHeaders("*")
    }
}
