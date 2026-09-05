package ai.govbiz.core.supportprogram.service.evidence

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramSourceDocument
import ai.govbiz.core.supportprogram.facade.AiSupportProgramEvidenceFacade
import ai.govbiz.core.supportprogram.facade.BizInfoSupportProgramSourceDocumentFacade
import ai.govbiz.core.supportprogram.facade.exception.SupportProgramSourceDocumentFacadeException
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.detail.SupportProgramDetailService
import ai.govbiz.core.supportprogram.service.dto.SupportProgramEvidenceAnswerResult
import ai.govbiz.core.supportprogram.service.evidence.exception.SupportProgramEvidenceNotSupportedException
import ai.govbiz.core.supportprogram.service.evidence.exception.SupportProgramEvidenceUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/** 특정 기업마당 공고의 공식 상세 원문을 근거로 질문에 답합니다. */
@Service
class SupportProgramEvidenceService(
    private val detailService: SupportProgramDetailService,
    private val repository: SupportProgramRepository,
    private val sourceDocumentFacade: BizInfoSupportProgramSourceDocumentFacade,
    private val aiEvidenceFacade: AiSupportProgramEvidenceFacade,
    @param:Qualifier("seoulClock") private val clock: Clock,
) {
    fun answer(
        sourceCode: String,
        sourceProgramId: String,
        question: String,
    ): SupportProgramEvidenceAnswerResult {
        val program = detailService.get(sourceCode, sourceProgramId)
        if (program.sourceCode != BIZINFO_SOURCE_CODE) throw SupportProgramEvidenceNotSupportedException()
        val document = currentSourceDocument(program)
        return aiEvidenceFacade.answer(
            question = question.trim(),
            chunks = chunksFor(document),
            sourceUrl = document.sourceUrl,
        )
    }

    private fun currentSourceDocument(program: SupportProgram): SupportProgramSourceDocument {
        val cached = try {
            repository.findPresentSourceDocument(program.sourceCode, program.id)
        } catch (_: IllegalArgumentException) {
            // 이전 버전에서 저장된 읽을 수 없는 원문은 재수집해 교체합니다.
            null
        }
        if (cached != null && cached.sourceUrl == program.sourceUrl && isFresh(cached)) return cached

        val loaded = try {
            sourceDocumentFacade.load(program)
        } catch (exception: SupportProgramSourceDocumentFacadeException) {
            throw SupportProgramEvidenceUnavailableException(exception)
        }
        // 외부 HTML을 모두 검증한 뒤에만 짧은 DB transaction으로 저장합니다.
        repository.upsertSourceDocument(loaded)
        return loaded
    }

    private fun chunksFor(document: SupportProgramSourceDocument): List<SupportProgramEvidenceChunk> =
        try {
            SupportProgramEvidenceChunker.chunk(document)
        } catch (exception: IllegalStateException) {
            throw SupportProgramEvidenceUnavailableException(exception)
        }

    private fun isFresh(document: SupportProgramSourceDocument): Boolean =
        !document.fetchedAt.isBefore(LocalDateTime.now(clock).minus(REFRESH_AFTER))

    private companion object {
        const val BIZINFO_SOURCE_CODE = "BIZINFO"
        val REFRESH_AFTER: Duration = Duration.ofHours(6)
    }
}
