package ai.govbiz.core.supportprogram.facade.config

import java.time.Clock
import java.time.ZoneId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 기업마당 공고의 접수 상태 계산에 사용하는 서울 기준 시계를 제공합니다. */
@Configuration(proxyBeanMethods = false)
class BizInfoSupportProgramCatalogFacadeConfig {

    @Bean
    fun seoulClock(): Clock = Clock.system(SEOUL_ZONE)

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
