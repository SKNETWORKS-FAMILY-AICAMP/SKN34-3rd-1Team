import { Link, useLocation, useParams } from 'react-router'
import type { ReactNode } from 'react'

import type { SupportProgram, SupportProgramStatus } from '../../../../domain/entities/SupportProgram'
import { supportProgramDetailStyles } from './SupportProgramDetailPage.styles'

type SupportProgramDetailLocationState = {
  program?: SupportProgram
}

/** 검색 결과에서 선택한 공고의 조건을 같은 앱 안에서 보여 주는 상세 화면입니다. */
export function SupportProgramDetailPage() {
  const location = useLocation()
  const { programId } = useParams()
  const program = getProgramFromLocationState(location.state, programId)

  if (!program) return <UnavailableSupportProgramDetail />

  return (
    <main className={supportProgramDetailStyles.page}>
      <header className={supportProgramDetailStyles.header}>
        <Link className={supportProgramDetailStyles.backLink} to="/">
          ← 검색 결과로 돌아가기
        </Link>
        <span className={supportProgramDetailStyles.sourceBadge}>{program.sourceName}</span>
      </header>

      <section className={supportProgramDetailStyles.hero} aria-labelledby="support-program-title">
        <div>
          <p className={supportProgramDetailStyles.eyebrow}>지원사업 상세</p>
          <h1 id="support-program-title" className={supportProgramDetailStyles.title}>
            {program.title}
          </h1>
          <p className={supportProgramDetailStyles.organization}>{program.organization}</p>
          <p className={supportProgramDetailStyles.summary}>{program.summary}</p>
        </div>
        <div className={supportProgramDetailStyles.statusCard}>
          <span className={supportProgramDetailStyles.statusLabel}>접수 상태</span>
          <strong className={supportProgramDetailStyles.statusValue}>
            {formatStatus(program.status)}
          </strong>
          <span className={supportProgramDetailStyles.score}>
            {program.recommendationScore === null
              ? '최신 공고'
              : `AI 추천 ${program.recommendationScore}점`}
          </span>
        </div>
      </section>

      <section className={supportProgramDetailStyles.details} aria-label="공고 조건">
        <DetailItem label="신청 기간">
          {program.applicationPeriod}
        </DetailItem>
        <DetailItem label="접수 시작일">
          {program.applicationStartDate ?? '별도 안내'}
        </DetailItem>
        <DetailItem label="접수 마감일">
          {program.applicationEndDate ?? '별도 안내'}
        </DetailItem>
        <DetailItem label="지원 대상">
          {program.targetDescription}
        </DetailItem>
        <DetailItem label="분야">
          <TagList values={program.categories} emptyLabel="분야 정보 없음" />
        </DetailItem>
        <DetailItem label="지역">
          <TagList values={program.regions} emptyLabel="지역 정보 없음" />
        </DetailItem>
      </section>

      <section className={supportProgramDetailStyles.reasonSection} aria-labelledby="recommendation-reasons">
        <p className={supportProgramDetailStyles.sectionEyebrow}>검색 결과</p>
        <h2 id="recommendation-reasons" className={supportProgramDetailStyles.sectionTitle}>
          이 공고를 추천한 이유
        </h2>
        {program.matchedReasons.length > 0 ? (
          <ul className={supportProgramDetailStyles.reasonList}>
            {program.matchedReasons.map((reason) => (
              <li key={reason} className={supportProgramDetailStyles.reason}>
                ✓ {reason}
              </li>
            ))}
          </ul>
        ) : (
          <p className={supportProgramDetailStyles.emptyReason}>
            검색 조건과 일치한 이유가 제공되지 않았습니다.
          </p>
        )}
      </section>

      <section className={supportProgramDetailStyles.sourceSection} aria-labelledby="source-information">
        <div>
          <p className={supportProgramDetailStyles.sourceEyebrow}>신청 전 확인</p>
          <h2 id="source-information" className={supportProgramDetailStyles.sourceTitle}>
            원문 공고에서 최종 조건을 확인하세요
          </h2>
          <p className={supportProgramDetailStyles.sourceDescription}>
            지원 자격, 제출 서류, 신청 방법은 공고 원문을 기준으로 합니다.
          </p>
        </div>
        <a
          className={supportProgramDetailStyles.sourceLink}
          href={program.sourceUrl}
          target="_blank"
          rel="noreferrer"
        >
          {program.sourceName} 원문 보기 ↗
        </a>
      </section>
    </main>
  )
}

function UnavailableSupportProgramDetail() {
  return (
    <main className={supportProgramDetailStyles.unavailablePage}>
      <Link className={supportProgramDetailStyles.backLink} to="/">
        ← 검색 결과로 돌아가기
      </Link>
      <section className={supportProgramDetailStyles.unavailableCard}>
        <p className={supportProgramDetailStyles.eyebrow}>지원사업 상세</p>
        <h1 className={supportProgramDetailStyles.title}>공고 정보를 찾을 수 없습니다</h1>
        <p className={supportProgramDetailStyles.unavailableDescription}>
          검색 결과에서 공고의 상세 조건 보기 버튼을 다시 선택해 주세요.
        </p>
      </section>
    </main>
  )
}

function DetailItem({ children, label }: { children: ReactNode; label: string }) {
  return (
    <article className={supportProgramDetailStyles.detailItem}>
      <h2 className={supportProgramDetailStyles.detailLabel}>{label}</h2>
      <div className={supportProgramDetailStyles.detailValue}>{children}</div>
    </article>
  )
}

function TagList({ emptyLabel, values }: { emptyLabel: string; values: string[] }) {
  if (values.length === 0) {
    return <span className={supportProgramDetailStyles.emptyValue}>{emptyLabel}</span>
  }

  return (
    <ul className={supportProgramDetailStyles.tagList}>
      {values.map((value) => (
        <li key={value} className={supportProgramDetailStyles.tag}>
          {value}
        </li>
      ))}
    </ul>
  )
}

function formatStatus(status: SupportProgramStatus) {
  const labels: Record<SupportProgramStatus, string> = {
    OPEN: '접수 중',
    UPCOMING: '접수 예정',
    CLOSED: '접수 마감',
    UNKNOWN: '상태 확인 필요',
  }
  return labels[status]
}

function getProgramFromLocationState(
  state: unknown,
  programId: string | undefined,
): SupportProgram | null {
  if (!isSupportProgramDetailLocationState(state)) return null
  if (!programId || programId !== state.program.id) return null
  return state.program
}

function isSupportProgramDetailLocationState(
  state: unknown,
): state is Required<SupportProgramDetailLocationState> {
  if (!isRecord(state)) return false
  return isSupportProgram(state.program)
}

function isSupportProgram(value: unknown): value is SupportProgram {
  if (!isRecord(value)) return false

  return typeof value.id === 'string'
    && typeof value.title === 'string'
    && typeof value.organization === 'string'
    && typeof value.summary === 'string'
    && isStringArray(value.categories)
    && isStringArray(value.regions)
    && typeof value.targetDescription === 'string'
    && typeof value.applicationPeriod === 'string'
    && isDateOrNull(value.applicationStartDate)
    && isDateOrNull(value.applicationEndDate)
    && isSupportProgramStatus(value.status)
    && typeof value.sourceName === 'string'
    && isSafeSourceUrl(value.sourceUrl)
    && isStringArray(value.matchedReasons)
    && isRecommendationScore(value.recommendationScore)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isDateOrNull(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isSupportProgramStatus(value: unknown): value is SupportProgramStatus {
  return value === 'OPEN' || value === 'UPCOMING' || value === 'CLOSED' || value === 'UNKNOWN'
}

function isSafeSourceUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false

  try {
    const url = new URL(value)
    const hostname = url.hostname.toLowerCase()
    return (url.protocol === 'https:' || url.protocol === 'http:')
      && (hostname === 'bizinfo.go.kr' || hostname.endsWith('.bizinfo.go.kr'))
  } catch {
    return false
  }
}

function isRecommendationScore(value: unknown): value is number | null {
  return value === null
    || (typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100)
}
