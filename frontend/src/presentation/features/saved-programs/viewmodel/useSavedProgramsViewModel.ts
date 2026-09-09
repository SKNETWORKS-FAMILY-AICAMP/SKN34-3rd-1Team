import { useState } from 'react'
import { createSavedProgramsPreview, monthDays, shiftMonth, type SavedProgramPreview } from './savedProgramsPreview'

function seoulToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}

/** UI 전용 상태입니다. 실제 API 연결 시 조회·저장 UseCase를 주입하며 공통 Hook/Redux는 변경하지 않습니다. */
export function useSavedProgramsViewModel() {
  const [today] = useState(seoulToday)
  const [programs] = useState(() => createSavedProgramsPreview(today))
  const [month, setMonth] = useState(today.slice(0, 7))
  const [view, setView] = useState<'calendar' | 'list'>('calendar')
  const [filters, setFilters] = useState({ keyword: '', region: '', category: '', hideClosed: true })
  const [page, setPage] = useState(1)
  const [selected, setSelected] = useState<SavedProgramPreview | null>(null)
  const [expandedDay, setExpandedDay] = useState<string | null>(null)
  const filtered = programs.filter((program) =>
    (!filters.hideClosed || !program.deadline || program.deadline >= today)
    && (!filters.region || program.region === filters.region)
    && (!filters.category || program.category === filters.category)
    && `${program.title} ${program.organization}`.includes(filters.keyword.trim()),
  )
  const dated = filtered.filter((program) => program.deadline?.startsWith(month))
  const undated = filtered.filter((program) => !program.deadline)
  const list = [...dated, ...undated]
  const pages = Math.max(1, Math.ceil(list.length / 5))
  const currentPage = Math.min(page, pages)
  function updateFilters(patch: Partial<typeof filters>) { setFilters((value) => ({ ...value, ...patch })); setPage(1); setExpandedDay(null) }
  return {
    today, programs, month, view, setView, filters, updateFilters,
    regions: [...new Set(programs.map((p) => p.region))], categories: [...new Set(programs.map((p) => p.category))],
    dated, undated, days: monthDays(month), pages, currentPage, setPage,
    visible: list.slice((currentPage - 1) * 5, currentPage * 5),
    changeMonth: (delta: number) => { setMonth((value) => shiftMonth(value, delta)); setPage(1); setExpandedDay(null) },
    goToday: () => { setMonth(today.slice(0, 7)); setPage(1); setExpandedDay(null) },
    selected, setSelected, expandedDay, setExpandedDay,
  }
}
