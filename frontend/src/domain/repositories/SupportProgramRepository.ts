import type { SupportProgram } from '../entities/SupportProgram'

export type SupportProgramSearch = {
  query: string
  acceptingOnly?: boolean
}

/** 서로 다른 제공처의 같은 원본 공고 ID를 구분하는 공개 식별자입니다. */
export type SupportProgramIdentity = {
  sourceCode: string
  sourceProgramId: string
}

/** 채팅 기능이 Data Layer의 구현 세부사항과 분리되도록 하는 Domain 포트입니다. */
export interface SupportProgramRepository {
  search(command: SupportProgramSearch, signal?: AbortSignal): Promise<SupportProgram[]>
  getDetail(identity: SupportProgramIdentity, signal?: AbortSignal): Promise<SupportProgram | null>
}
