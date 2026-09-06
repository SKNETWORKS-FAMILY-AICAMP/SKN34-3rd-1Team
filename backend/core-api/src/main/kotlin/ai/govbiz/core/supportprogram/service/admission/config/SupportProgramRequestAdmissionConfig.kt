package ai.govbiz.core.supportprogram.service.admission.config

import ai.govbiz.core.supportprogram.service.admission.SupportProgramRequestAdmissionService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportProgramRequestAdmissionProperties::class)
class SupportProgramRequestAdmissionConfig {
    @Bean
    fun supportProgramRequestAdmissionService(
        properties: SupportProgramRequestAdmissionProperties,
    ) = SupportProgramRequestAdmissionService(properties)
}
