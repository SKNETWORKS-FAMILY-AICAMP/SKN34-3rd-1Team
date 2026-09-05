/** 검색 화면이 공고 데이터와 검색 인덱스를 사용할 수 있는지 나타냅니다. */
export type SupportProgramSearchState =
  | 'PREPARING'
  | 'SEARCHABLE'
  | 'SEARCHABLE_WITH_SYNC_FAILURE'
  | 'UNAVAILABLE'

/** 검색 요청 전 사용자에게 보여 줄 지원사업 데이터 준비 상태입니다. */
export type SupportProgramSearchReadiness = {
  searchState: SupportProgramSearchState
  programCount: number
  indexReady: boolean
  lastSuccessfulSyncAt: string | null
  lastFailedSyncAt: string | null
}
