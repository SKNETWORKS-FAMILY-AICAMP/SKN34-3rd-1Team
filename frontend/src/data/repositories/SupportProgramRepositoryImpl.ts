import {
  answerSupportProgramEvidenceQuestionApi,
  getSupportProgramDetailApi,
  getSupportProgramSearchReadinessApi,
  searchSupportProgramsApi,
  SupportProgramEvidenceApiError,
  SupportProgramRequestApiError,
} from '../api/supportProgramApi'
import { toSupportProgram } from '../models/SupportProgramDto'
import { toSupportProgramEvidenceAnswer } from '../models/SupportProgramEvidenceAnswerDto'
import { toSupportProgramSearchReadiness } from '../models/SupportProgramSearchReadinessDto'
import type { SupportProgram } from '../../domain/entities/SupportProgram'
import { SupportProgramRequestError } from '../../domain/errors/SupportProgramRequestError'
import type { SupportProgramSearchReadiness } from '../../domain/entities/SupportProgramSearchReadiness'
import type {
  SupportProgramRepository,
  SupportProgramEvidenceQuestion,
  SupportProgramEvidenceQuestionResult,
  SupportProgramIdentity,
  SupportProgramSearch,
} from '../../domain/repositories/SupportProgramRepository'

/** Core API DTO를 검증된 Domain 공고로 변환하는 Repository adapter입니다. */
export class SupportProgramRepositoryImpl implements SupportProgramRepository {
  async search(
    command: SupportProgramSearch,
    signal?: AbortSignal,
  ): Promise<SupportProgram[]> {
    try {
      const response = await searchSupportProgramsApi(command, signal)
      return response.programs.map(toSupportProgram)
    } catch (error) {
      throw toRequestError(error)
    }
  }

  async getSearchReadiness(signal?: AbortSignal): Promise<SupportProgramSearchReadiness> {
    return toSupportProgramSearchReadiness(
      await getSupportProgramSearchReadinessApi(signal),
    )
  }

  async getDetail(
    identity: SupportProgramIdentity,
    signal?: AbortSignal,
  ): Promise<SupportProgram | null> {
    const dto = await getSupportProgramDetailApi(identity, signal)
    return dto ? toSupportProgram(dto) : null
  }

  async answerEvidenceQuestion(
    command: SupportProgramEvidenceQuestion,
    signal?: AbortSignal,
  ): Promise<SupportProgramEvidenceQuestionResult> {
    try {
      const dto = await answerSupportProgramEvidenceQuestionApi(command, signal)
      return { outcome: 'answer', answer: toSupportProgramEvidenceAnswer(dto) }
    } catch (error) {
      if (error instanceof SupportProgramEvidenceApiError) {
        if (error.status === 422) return { outcome: 'not-supported' }
        if (error.status === 503) return { outcome: 'unavailable' }
      }
      throw toRequestError(error)
    }
  }
}

function toRequestError(error: unknown): unknown {
  return error instanceof SupportProgramRequestApiError
    ? new SupportProgramRequestError(
      error.code === 'SUPPORT_PROGRAM_RATE_LIMITED' ? 'rate-limited' : 'busy',
      error.retryAfterSeconds,
    )
    : error
}
