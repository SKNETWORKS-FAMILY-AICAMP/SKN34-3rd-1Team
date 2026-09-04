package ai.govbiz.core.supportprogram.service.sync

import ai.govbiz.core.supportprogram.facade.SupportProgramCatalogFacade
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import org.springframework.stereotype.Service

/** 기업마당 지원사업 전체 목록을 수집한 뒤 MySQL에 하나의 스냅샷으로 반영합니다. */
@Service
class BizInfoSupportProgramCatalogSyncService(
    private val catalogFacade: SupportProgramCatalogFacade,
    private val supportProgramRepository: SupportProgramRepository,
) {
    fun sync(): Int {
        val programs = catalogFacade.load()
        supportProgramRepository.synchronizeBizInfo(programs)

        return programs.size
    }
}
