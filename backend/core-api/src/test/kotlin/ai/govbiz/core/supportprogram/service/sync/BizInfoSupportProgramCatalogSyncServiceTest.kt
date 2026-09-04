package ai.govbiz.core.supportprogram.service.sync

import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.facade.SupportProgramCatalogFacade
import ai.govbiz.core.supportprogram.facade.exception.SupportProgramCatalogFacadeException
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class BizInfoSupportProgramCatalogSyncServiceTest {

    @Mock
    private lateinit var catalogFacade: SupportProgramCatalogFacade

    @Mock
    private lateinit var supportProgramRepository: SupportProgramRepository

    @Test
    fun collectsTheWholeSnapshotBeforePassingItToTheRepository() {
        val programs = listOf(catalogProgram("first"), catalogProgram("second"))
        doReturn(programs).`when`(catalogFacade).load()

        val synchronizedCount = service().sync()

        assertEquals(2, synchronizedCount)
        inOrder(catalogFacade, supportProgramRepository).apply {
            verify(catalogFacade).load()
            verify(supportProgramRepository).synchronizeBizInfo(programs)
            verifyNoMoreInteractions()
        }
    }

    @Test
    fun doesNotTouchTheRepositoryWhenCatalogCollectionFails() {
        val failure = SupportProgramCatalogFacadeException.fromClient(
            failure = SupportProgramCatalogFacadeException.Failure.UPSTREAM_ERROR,
            message = "기업마당 지원사업 목록 수집 실패",
            cause = IllegalStateException("upstream failure"),
        )
        doThrow(failure).`when`(catalogFacade).load()

        val thrown = assertThrows(SupportProgramCatalogFacadeException::class.java) {
            service().sync()
        }

        assertSame(failure, thrown)
        verifyNoInteractions(supportProgramRepository)
    }

    private fun service() = BizInfoSupportProgramCatalogSyncService(
        catalogFacade = catalogFacade,
        supportProgramRepository = supportProgramRepository,
    )

    private fun catalogProgram(id: String) = CatalogSupportProgram(
        program = SupportProgram(
            id = id,
            title = "$id 지원사업",
            organization = "수행기관",
            summary = "지원 내용",
            categories = listOf("AI"),
            regions = listOf("서울"),
            targetDescription = "중소기업",
            applicationPeriod = "상시 접수",
            applicationStartDate = null,
            applicationEndDate = null,
            status = SupportProgramStatus.OPEN,
            sourceName = "기업마당",
            sourceUrl = "https://www.bizinfo.go.kr/detail?id=$id",
            matchedReasons = emptyList(),
            recommendationScore = null,
        ),
        sortTimestamp = "2026-09-04 10:00:00",
    )
}
