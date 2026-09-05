import type { SupportProgram } from '../entities/SupportProgram'
import type { SupportProgramEvidenceAnswer } from '../entities/SupportProgramEvidenceAnswer'

export type SupportProgramSearch = {
  query: string
  acceptingOnly?: boolean
}

/** 서로 다른 제공처의 같은 원본 공고 ID를 구분하는 공개 식별자입니다. */
export type SupportProgramIdentity = {
  sourceCode: string
  sourceProgramId: string
}

/** 특정 공고의 공식 원문에 대해 사용자가 직접 보낸 질문입니다. */
export type SupportProgramEvidenceQuestion = SupportProgramIdentity & {
  question: string
}

/** 제공처 지원 여부와 일시 장애는 답변 없음과 구분되는 사용자 흐름입니다. */
export type SupportProgramEvidenceQuestionResult =
  | { outcome: 'answer'; answer: SupportProgramEvidenceAnswer }
  | { outcome: 'not-supported' }
  | { outcome: 'unavailable' }

/** 채팅 기능이 Data Layer의 구현 세부사항과 분리되도록 하는 Domain 포트입니다. */
export interface SupportProgramRepository {
  search(command: SupportProgramSearch, signal?: AbortSignal): Promise<SupportProgram[]>
  getDetail(identity: SupportProgramIdentity, signal?: AbortSignal): Promise<SupportProgram | null>
  answerEvidenceQuestion(
    command: SupportProgramEvidenceQuestion,
    signal?: AbortSignal,
  ): Promise<SupportProgramEvidenceQuestionResult>
}
