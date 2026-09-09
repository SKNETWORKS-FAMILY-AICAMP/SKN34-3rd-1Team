/** 배치·필터 확인용 예시입니다. 실제 공고, 회원 저장 내역, AI 결과가 아닙니다. */
export type SavedProgramPreview = {
  id: string
  title: string
  organization: string
  region: string
  category: string
  deadline: string | null
  periodType: 'dated' | 'rolling' | 'unknown'
}

export function addDays(date: string, days: number): string {
  const result = new Date(`${date}T00:00:00Z`)
  result.setUTCDate(result.getUTCDate() + days)
  return result.toISOString().slice(0, 10)
}

export function createSavedProgramsPreview(today: string): SavedProgramPreview[] {
  const rows: [string, string, string, string, number | null, SavedProgramPreview['periodType']][] = [
    ['AI 창업기업 사업화 지원', '서울경제진흥원', '서울', '창업', 3, 'dated'],
    ['수출 바우처 지원', '중소벤처기업진흥공단', '전국', '수출', 7, 'dated'],
    ['스마트공장 구축 지원', '중소벤처기업부', '전국', '기술', 9, 'dated'],
    ['창업 성장 지원', '서울경제진흥원', '서울', '창업', 9, 'dated'],
    ['기술사업화 지원', '경기경제과학진흥원', '경기', '기술', 9, 'dated'],
    ['해외 인증 취득 지원', '중소벤처기업진흥공단', '전국', '수출', 9, 'dated'],
    ['해외진출 상담 지원', '예시 지원기관', '전국', '수출', null, 'rolling'],
    ['지역 기술개발 지원', '예시 지원기관', '경기', '기술', null, 'unknown'],
    ['이전 창업 지원', '예시 지원기관', '서울', '창업', -2, 'dated'],
  ]
  return rows.map(([title, organization, region, category, days, periodType], index) => ({
    id: `preview-${index}`, title, organization, region, category,
    deadline: days === null ? null : addDays(today, days), periodType,
  }))
}

export function monthDays(month: string): string[] {
  const first = `${month}-01`
  const [year, number] = month.split('-').map(Number)
  const offset = new Date(`${first}T00:00:00Z`).getUTCDay()
  const length = new Date(Date.UTC(year, number, 0)).getUTCDate()
  return Array.from({ length: Math.ceil((offset + length) / 7) * 7 }, (_, index) => addDays(first, index - offset))
}

export function shiftMonth(month: string, delta: number): string {
  const [year, number] = month.split('-').map(Number)
  return new Date(Date.UTC(year, number - 1 + delta, 1)).toISOString().slice(0, 7)
}

export function previewDeadlineLabel(program: SavedProgramPreview, today: string): string {
  if (!program.deadline) return program.periodType === 'rolling' ? '상시 접수' : '마감일 미확인'
  const days = Math.round((Date.parse(`${program.deadline}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000)
  return days < 0 ? '접수 마감' : days === 0 ? '오늘 마감' : `D-${days}`
}
