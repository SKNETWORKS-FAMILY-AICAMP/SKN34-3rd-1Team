package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram

/** 검색 유스케이스에 정규화된 지원사업 후보를 제공하는 Facade 계약입니다. */
fun interface SupportProgramCatalogFacade {
    fun load(): List<CatalogSupportProgram>
}
