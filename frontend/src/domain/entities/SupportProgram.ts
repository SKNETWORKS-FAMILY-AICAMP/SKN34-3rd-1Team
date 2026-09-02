export type SupportProgramStatus = 'OPEN' | 'UPCOMING' | 'CLOSED' | 'UNKNOWN'

export type SupportProgram = {
  id: string
  title: string
  organization: string
  summary: string
  categories: string[]
  regions: string[]
  targetDescription: string
  applicationPeriod: string
  applicationStartDate: string | null
  applicationEndDate: string | null
  status: SupportProgramStatus
  sourceName: string
  sourceUrl: string
  matchedReasons: string[]
  recommendationScore: number | null
}
