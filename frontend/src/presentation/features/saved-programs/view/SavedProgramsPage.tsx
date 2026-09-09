import { useEffect, useRef } from 'react'
import { Link } from 'react-router'
import { appPaths } from '../../../shared/routes/appPaths'
import { previewDeadlineLabel, type SavedProgramPreview } from '../viewmodel/savedProgramsPreview'
import { useSavedProgramsViewModel } from '../viewmodel/useSavedProgramsViewModel'
import s from './SavedProgramsPage.module.css'

export function SavedProgramsPage() {
  const vm = useSavedProgramsViewModel()
  const row = (program: SavedProgramPreview) => <button type="button" className={s.row} key={program.id} onClick={() => vm.setSelected(program)}>
    <span className={s.badge}>{previewDeadlineLabel(program, vm.today)}</span><span><strong>{program.title}</strong><small>{program.organization} · {program.region} · {program.category}</small></span><span aria-hidden="true">›</span>
  </button>
  return <main className={s.page}>
    <header className={s.header}><div><p className={s.eyebrow}>MY WORKSPACE</p><h1>관심 공고함</h1><p>저장한 지원사업의 마감일을 한눈에 살펴보세요.</p></div><Link className={s.primary} to={`${appPaths.chat}?mode=filter`}>＋ 공고 찾기</Link></header>
    <p className={s.preview}>UI 시안 · 모든 공고와 날짜는 예시입니다. 실제 즐겨찾기 저장·AI 검토·비교는 아직 연결하지 않았습니다.</p>
    <section className={s.filters} aria-label="관심 공고 필터">
      <label className={s.search}>공고 검색<input type="search" placeholder="공고명 또는 기관명" value={vm.filters.keyword} onChange={(event) => vm.updateFilters({ keyword: event.target.value })} /></label>
      <label>지역<select value={vm.filters.region} onChange={(event) => vm.updateFilters({ region: event.target.value })}><option value="">전체 지역</option>{vm.regions.map((value) => <option key={value}>{value}</option>)}</select></label>
      <label>분야<select value={vm.filters.category} onChange={(event) => vm.updateFilters({ category: event.target.value })}><option value="">전체 분야</option>{vm.categories.map((value) => <option key={value}>{value}</option>)}</select></label>
      <label className={s.checkbox}><input type="checkbox" checked={vm.filters.hideClosed} onChange={(event) => vm.updateFilters({ hideClosed: event.target.checked })} />마감 공고 제외</label>
    </section>
    <div className={s.toolbar}><span>관심 공고 <strong>{vm.programs.length}</strong>건 · 예시</span><div className={s.switch} aria-label="보기 방식"><button type="button" aria-pressed={vm.view === 'calendar'} onClick={() => vm.setView('calendar')}>▦ 달력</button><button type="button" aria-pressed={vm.view === 'list'} onClick={() => vm.setView('list')}>☷ 목록</button></div></div>
    <section className={s.surface} aria-label="관심 공고 일정">
      <div className={s.monthBar}><div><button type="button" aria-label="이전 달" disabled={vm.month <= '1900-01'} onClick={() => vm.changeMonth(-1)}>‹</button><h2>{Number(vm.month.slice(0, 4))}년 {Number(vm.month.slice(5))}월</h2><button type="button" aria-label="다음 달" disabled={vm.month >= '2100-12'} onClick={() => vm.changeMonth(1)}>›</button></div><button type="button" className={s.secondary} onClick={vm.goToday}>이번 달</button></div>
      <p className={s.note}>마감일 기준 · 이번 달 {vm.dated.length}건 · 날짜 미정 {vm.undated.length}건</p>
      {vm.view === 'calendar' ? <div className={s.calendarScroll}><div className={s.calendar}>
        {['일', '월', '화', '수', '목', '금', '토'].map((value) => <div key={value} className={s.weekday}>{value}</div>)}
        {vm.days.map((date) => {
          const inMonth = date.startsWith(vm.month)
          const programs = inMonth ? vm.dated.filter((p) => p.deadline === date) : []
          const expanded = vm.expandedDay === date
          return <div className={`${s.day} ${inMonth ? '' : s.outside}`} key={date}>
            <span className={date === vm.today ? s.today : s.date}>{Number(date.slice(-2))}</span>
            {programs.slice(0, expanded ? programs.length : 3).map((program) => <button type="button" className={`${s.event} ${date < vm.today ? s.closed : ''}`} key={program.id} aria-label={`${date} 마감 ${program.title}`} onClick={() => vm.setSelected(program)}>▮ {program.title}</button>)}
            {programs.length > 3 && <button type="button" className={s.more} onClick={() => vm.setExpandedDay(expanded ? null : date)}>{expanded ? '접기' : `+${programs.length - 3}개 더 보기`}</button>}
          </div>
        })}
      </div></div> : <div className={s.list}><div>{vm.visible.length ? vm.visible.map(row) : <p className={s.empty}>이 달과 조건에 해당하는 공고가 없어요.</p>}</div><nav className={s.pagination} aria-label="관심 공고 페이지"><button type="button" disabled={vm.currentPage === 1} onClick={() => vm.setPage(vm.currentPage - 1)}>이전</button>{Array.from({ length: vm.pages }, (_, i) => i + 1).map((number) => <button type="button" aria-label={`${number}페이지`} aria-current={number === vm.currentPage ? 'page' : undefined} key={number} onClick={() => vm.setPage(number)}>{number}</button>)}<button type="button" disabled={vm.currentPage === vm.pages} onClick={() => vm.setPage(vm.currentPage + 1)}>다음</button></nav></div>}
    </section>
    {vm.view === 'calendar' && <section className={s.surface} aria-label="상시 접수와 날짜 미확인"><div className={s.sectionTitle}><h2>날짜가 정해지지 않은 공고</h2><p>상시 접수와 마감일 미확인을 구분해 보여드려요.</p></div>{vm.undated.length ? vm.undated.map(row) : <p className={s.empty}>해당하는 공고가 없어요.</p>}</section>}
    {vm.selected && <PreviewDetail program={vm.selected} today={vm.today} close={() => vm.setSelected(null)} />}
  </main>
}

function PreviewDetail({ program, today, close }: { program: SavedProgramPreview; today: string; close: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { const element = dialog.current; element?.showModal(); return () => element?.close() }, [])
  return <dialog ref={dialog} className={s.drawer} aria-label="예시 공고 상세" onCancel={close}>
    <div className={s.drawerHeader}><span>선택한 공고 · 예시</span><button type="button" aria-label="상세 닫기" onClick={close}>×</button></div>
    <h2>{program.title}</h2><p>{program.organization}</p><span className={s.badge}>{previewDeadlineLabel(program, today)}</span>
    <dl><div><dt>지역</dt><dd>{program.region}</dd></div><div><dt>분야</dt><dd>{program.category}</dd></div><div><dt>마감일</dt><dd>{program.deadline ?? previewDeadlineLabel(program, today)}</dd></div></dl>
    <p className={s.preview}>현재는 배치 확인용입니다. 실제 저장·자격요건·AI-SCORE·비교는 다음 단계에 연결합니다.</p>
  </dialog>
}
