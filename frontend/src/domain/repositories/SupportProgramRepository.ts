import type { SupportProgram } from '../entities/SupportProgram'

export type SupportProgramSearch = {
  query: string
  acceptingOnly?: boolean
}

/** 채팅 기능이 Data Layer의 구현 세부사항과 분리되도록 하는 Domain 포트입니다. */
export interface SupportProgramRepository {
  search(command: SupportProgramSearch, signal?: AbortSignal): Promise<SupportProgram[]>
}
