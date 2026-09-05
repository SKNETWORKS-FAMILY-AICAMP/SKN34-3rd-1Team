package ai.govbiz.core.supportprogram.service.sync

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "app.support-program-index", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SupportProgramIndexSyncScheduler(private val syncService: SupportProgramIndexSyncService) {
    @Scheduled(
        initialDelayString = "\${app.support-program-index.initial-delay}",
        fixedDelayString = "\${app.support-program-index.fixed-delay}",
        scheduler = "supportProgramIndexTaskScheduler",
    )
    fun synchronize() {
        try {
            val count = syncService.sync()
            logger.info("지원사업 {}건의 벡터 색인을 동기화했습니다.", count)
        } catch (exception: RuntimeException) {
            logger.error("지원사업 색인 동기화 실패: {}. 다음 실행에서 다시 시도합니다.", exception.javaClass.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SupportProgramIndexSyncScheduler::class.java)
    }
}
