import { type FormEvent, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'

import type { SupportProgram } from '../../../../domain/entities/SupportProgram'
import type { SupportProgramSearchReadiness } from '../../../../domain/entities/SupportProgramSearchReadiness'
import {
  supportProgramChatSuggestions,
  useSupportProgramChatViewModel,
} from '../viewmodel/useSupportProgramChatViewModel'
import { useSupportProgramSearchReadinessViewModel } from '../viewmodel/useSupportProgramSearchReadinessViewModel'
import {
  chatBackdropClassName,
  chatMessageBubbleClassName,
  chatMessageRowClassName,
  chatPageStyles,
  chatSidebarClassName,
} from './ChatPage.styles'

export function ChatPage() {
  const readiness = useSupportProgramSearchReadinessViewModel()
  const {
    canRetrySearch,
    conversationCount,
    cancelSearch,
    draft,
    isReadyToSubmit,
    isSearching,
    messages,
    searchError,
    selectSuggestion,
    startNewConversation,
    submitMessage,
    updateDraft,
  } = useSupportProgramChatViewModel()
  const [isSidebarOpen, setIsSidebarOpen] = useState(false)
  const isComposingInput = useRef(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const sidebarRef = useRef<HTMLElement>(null)
  const shouldRestoreMenuFocusRef = useRef(false)
  const timelineRef = useRef<HTMLDivElement>(null)
  const latestMessage = messages.at(-1)
  const searchStatusAnnouncement = isSearching
    ? '지원사업 공고를 검색하고 있습니다.'
    : latestMessage?.role === 'assistant' && latestMessage.programs
      ? `지원사업 검색 결과 ${latestMessage.programs.length}건을 표시했습니다.`
      : ''

  useEffect(() => {
    const timeline = timelineRef.current
    if (timeline) timeline.scrollTop = timeline.scrollHeight
  }, [messages, isSearching])

  useEffect(() => {
    if (!isSidebarOpen) {
      if (shouldRestoreMenuFocusRef.current) {
        menuButtonRef.current?.focus()
        shouldRestoreMenuFocusRef.current = false
      }
      return
    }

    const sidebar = sidebarRef.current
    if (!sidebar) return

    focusFirstSidebarElement(sidebar)

    function handleSidebarKeyboardNavigation(event: KeyboardEvent) {
      const currentSidebar = sidebarRef.current
      if (!currentSidebar) return

      if (event.key === 'Escape') {
        event.preventDefault()
        closeSidebar()
        return
      }
      if (event.key !== 'Tab') return

      const focusableElements = getSidebarFocusableElements(currentSidebar)
      if (focusableElements.length === 0) {
        event.preventDefault()
        currentSidebar.focus()
        return
      }

      const firstElement = focusableElements[0]
      const lastElement = focusableElements.at(-1)
      const activeElement = document.activeElement
      const isFocusInsideSidebar = currentSidebar.contains(activeElement)
      const shouldMoveToFirst = !event.shiftKey && (
        activeElement === lastElement || !isFocusInsideSidebar
      )
      const shouldMoveToLast = event.shiftKey && (
        activeElement === firstElement || activeElement === currentSidebar || !isFocusInsideSidebar
      )

      if (shouldMoveToFirst) {
        event.preventDefault()
        firstElement.focus()
      }
      if (shouldMoveToLast && lastElement) {
        event.preventDefault()
        lastElement.focus()
      }
    }

    document.addEventListener('keydown', handleSidebarKeyboardNavigation)
    return () => document.removeEventListener('keydown', handleSidebarKeyboardNavigation)
  }, [isSidebarOpen])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!readiness.canSearch) return
    void submitMessage()
  }

  function handleStartNewConversation() {
    startNewConversation()
    closeSidebar()
  }

  function handleSelectSuggestion(suggestion: string) {
    if (!readiness.canSearch) return
    selectSuggestion(suggestion)
    closeSidebar()
  }

  function handleRetrySearch() {
    if (!readiness.canSearch) return
    void submitMessage()
  }

  function openSidebar() {
    shouldRestoreMenuFocusRef.current = false
    setIsSidebarOpen(true)
  }

  function closeSidebar() {
    shouldRestoreMenuFocusRef.current = true
    setIsSidebarOpen(false)
  }

  return (
    <main className={chatPageStyles.page}>
      <div
        className={chatBackdropClassName(isSidebarOpen)}
        aria-hidden="true"
        onClick={closeSidebar}
      />

      <aside
        ref={sidebarRef}
        id="chat-sidebar"
        className={chatSidebarClassName(isSidebarOpen)}
        aria-label="지원사업 검색 메뉴"
        aria-modal={isSidebarOpen || undefined}
        role={isSidebarOpen ? 'dialog' : undefined}
        tabIndex={-1}
      >
        <button
          type="button"
          className={chatPageStyles.sidebarCloseButton}
          aria-label="메뉴 닫기"
          onClick={closeSidebar}
        >
          ×
        </button>
        <div className={chatPageStyles.brand}>
          <span className={chatPageStyles.brandMark}>
            G
          </span>
          <div>
            <strong className={chatPageStyles.brandTitle}>GovBiz</strong>
            <span className={chatPageStyles.brandSubtitle}>
              지원사업 탐색 도우미
            </span>
          </div>
        </div>

        <div className={chatPageStyles.sidebarActions}>
          <button
            className={chatPageStyles.newConversationButton}
            type="button"
            onClick={handleStartNewConversation}
          >
            <span className={chatPageStyles.newConversationIcon}>＋</span>
            새 대화 시작
          </button>
          <Link
            className={chatPageStyles.sampleButton}
            to="/examples/sample-item/hook"
          >
            <span className={chatPageStyles.sampleButtonIcon}>▦</span>
            상태관리 비교 예제
          </Link>
        </div>

        <div className={chatPageStyles.popularQuestions}>
          <p className={chatPageStyles.sidebarSectionTitle}>
            추천 질문
          </p>
          {supportProgramChatSuggestions.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              className={chatPageStyles.popularQuestionButton}
              onClick={() => handleSelectSuggestion(suggestion)}
              disabled={!readiness.canSearch}
            >
              {suggestion}
            </button>
          ))}
        </div>

        <div className={chatPageStyles.dataSummary}>
          <p className={chatPageStyles.dataSummaryTitle}>
            공고 데이터
          </p>
          <div className={chatPageStyles.dataSummaryCard}>
            <strong className={chatPageStyles.dataSummaryValue}>
              {readiness.data ? `${readiness.data.programCount}건` : '확인 중'}
            </strong>
            <span className={chatPageStyles.dataSummaryLabel}>현재 동기화된 공고</span>
          </div>
          <div className={chatPageStyles.dataSummaryCard}>
            <strong className={chatPageStyles.dataSummaryValue}>{conversationCount}</strong>
            <span className={chatPageStyles.dataSummaryLabel}>
              이번 대화 검색
            </span>
          </div>
        </div>

        <p className={chatPageStyles.sidebarFooter}>
          검색 결과는 기업마당 공식 공고와 원문 링크를 기반으로 합니다.
        </p>
      </aside>

      <section className={chatPageStyles.workspace} inert={isSidebarOpen}>
        <header className={chatPageStyles.header}>
          <button
            ref={menuButtonRef}
            type="button"
            className={chatPageStyles.menuButton}
            aria-controls="chat-sidebar"
            aria-expanded={isSidebarOpen}
            aria-label="메뉴 열기"
            onClick={openSidebar}
          >
            ☰
          </button>
          <div>
            <p className={chatPageStyles.headerEyebrow}>
              지원사업 검색
            </p>
            <h1 className={chatPageStyles.headerTitle}>
              GovBiz에게 물어보세요
            </h1>
          </div>
          <span className={chatPageStyles.sourceBadge}>
            기업마당 공식 데이터
          </span>
        </header>

        <div
          className={chatPageStyles.timeline}
          ref={timelineRef}
        >
          <p
            className={chatPageStyles.searchStatus}
            role="status"
            aria-live="polite"
            aria-atomic="true"
          >
            {searchStatusAnnouncement}
          </p>
          {messages.map((message) => {
            const isUser = message.role === 'user'

            return (
              <article
                key={message.id}
                className={chatMessageRowClassName(isUser)}
              >
                {!isUser ? (
                  <span className={chatPageStyles.assistantAvatar}>
                    G
                  </span>
                ) : null}
                <div className={chatPageStyles.messageContent}>
                  <div className={chatMessageBubbleClassName(isUser)}>
                    {message.text}
                  </div>
                  {message.id === messages[0]?.id ? (
                    <div className={chatPageStyles.suggestedQuestions}>
                      {supportProgramChatSuggestions.map((suggestion) => (
                        <button
                          key={suggestion}
                          type="button"
                          className={chatPageStyles.suggestedQuestionButton}
                          onClick={() => handleSelectSuggestion(suggestion)}
                          disabled={!readiness.canSearch}
                        >
                          {suggestion}
                        </button>
                      ))}
                    </div>
                  ) : null}
                  {message.programs?.length ? (
                    <div className={chatPageStyles.programList}>
                      {message.programs.map((program) => (
                        <ProgramCard key={`${program.sourceCode}:${program.id}`} program={program} />
                      ))}
                    </div>
                  ) : null}
                </div>
              </article>
            )
          })}
          {isSearching ? (
            <div className={chatPageStyles.messageRow}>
              <span className={chatPageStyles.assistantAvatar}>
                G
              </span>
              <div className={chatPageStyles.searchingBubble}>
                공고를 찾아보고 있어요…
              </div>
            </div>
          ) : null}
        </div>

        <form
          className={chatPageStyles.composer}
          onSubmit={handleSubmit}
        >
          <SupportProgramSearchReadinessNotice
            readiness={readiness.data}
            isError={readiness.isError}
            isInitialLoading={readiness.isInitialLoading}
            isRefreshing={readiness.isRefreshing}
            onRetry={() => {
              void readiness.refetch()
            }}
          />
          {searchError ? (
            <div className={chatPageStyles.searchError} role="alert">
              <span>{searchError}</span>
              {canRetrySearch && readiness.canSearch ? (
                <button
                  type="button"
                  className={chatPageStyles.searchRetryButton}
                  onClick={handleRetrySearch}
                >
                  다시 검색
                </button>
              ) : null}
            </div>
          ) : null}
          <textarea
            className={chatPageStyles.composerInput}
            aria-label="지원사업 검색어"
            aria-describedby="support-program-search-readiness"
            value={draft}
            disabled={!readiness.canSearch}
            onChange={(event) => updateDraft(event.target.value)}
            onCompositionStart={() => {
              isComposingInput.current = true
            }}
            onCompositionEnd={() => {
              isComposingInput.current = false
            }}
            onKeyDown={(event) => {
              if (
                isComposingInput.current ||
                event.nativeEvent.isComposing ||
                event.nativeEvent.keyCode === 229
              ) return
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                event.currentTarget.form?.requestSubmit()
              }
            }}
            placeholder="예: 서울에서 AI 창업지원 사업을 찾아줘"
            rows={1}
          />
          {isSearching ? (
            <button
              type="button"
              className={chatPageStyles.cancelSearchButton}
              onClick={cancelSearch}
            >
              취소
            </button>
          ) : (
            <button
              type="submit"
              className={chatPageStyles.submitButton}
              aria-label="검색 전송"
              disabled={!isReadyToSubmit || !readiness.canSearch}
            >
              ↑
            </button>
          )}
          <small className={chatPageStyles.composerHint}>
            Enter로 전송 · Shift+Enter로 줄바꿈
          </small>
        </form>
      </section>
    </main>
  )
}

type SupportProgramSearchReadinessNoticeProps = {
  readiness: SupportProgramSearchReadiness | undefined
  isError: boolean
  isInitialLoading: boolean
  isRefreshing: boolean
  onRetry: () => void
}

function SupportProgramSearchReadinessNotice({
  readiness,
  isError,
  isInitialLoading,
  isRefreshing,
  onRetry,
}: SupportProgramSearchReadinessNoticeProps) {
  if (isInitialLoading) {
    return (
      <section
        id="support-program-search-readiness"
        className={chatPageStyles.readinessNotice}
        aria-live="polite"
        aria-atomic="true"
      >
        공고 데이터 상태를 확인하고 있습니다.
      </section>
    )
  }

  if (isError || !readiness) {
    return (
      <section
        id="support-program-search-readiness"
        className={chatPageStyles.readinessErrorNotice}
        role="alert"
      >
        <span>공고 데이터 상태를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.</span>
        <button
          type="button"
          className={chatPageStyles.readinessRetryButton}
          onClick={onRetry}
        >
          상태 다시 확인
        </button>
      </section>
    )
  }

  const content = getReadinessNoticeContent(readiness)
  const isUnavailable = readiness.searchState === 'UNAVAILABLE'

  return (
    <section
      id="support-program-search-readiness"
      className={isUnavailable
        ? chatPageStyles.readinessErrorNotice
        : chatPageStyles.readinessNotice}
      role={isUnavailable ? 'alert' : undefined}
      aria-live={isUnavailable ? undefined : 'polite'}
    >
      <div>
        <strong className={chatPageStyles.readinessTitle}>{content.title}</strong>
        <p className={chatPageStyles.readinessDescription}>{content.description}</p>
        <dl className={chatPageStyles.readinessDetails}>
          <div>
            <dt>현재 동기화된 공고</dt>
            <dd>{readiness.programCount}건</dd>
          </div>
          <div>
            <dt>검색 인덱스</dt>
            <dd>{readiness.indexReady ? '준비됨' : '준비 중'}</dd>
          </div>
          <div>
            <dt>마지막 성공 동기화</dt>
            <dd>{formatSyncTime(readiness.lastSuccessfulSyncAt)}</dd>
          </div>
          <div>
            <dt>마지막 실패 동기화</dt>
            <dd>{formatSyncTime(readiness.lastFailedSyncAt)}</dd>
          </div>
        </dl>
      </div>
      {isUnavailable ? (
        <button
          type="button"
          className={chatPageStyles.readinessRetryButton}
          onClick={onRetry}
        >
          상태 다시 확인
        </button>
      ) : null}
      {isRefreshing ? (
        <span className={chatPageStyles.readinessRefreshing}>상태를 다시 확인하고 있습니다.</span>
      ) : null}
    </section>
  )
}

function getReadinessNoticeContent(readiness: SupportProgramSearchReadiness) {
  switch (readiness.searchState) {
    case 'PREPARING':
      return {
        title: '초기 공고 데이터를 준비하고 있습니다.',
        description: '준비가 완료되면 자동으로 검색할 수 있습니다.',
      }
    case 'SEARCHABLE':
      if (readiness.programCount === 0) {
        return {
          title: '현재 제공 중인 공고가 없습니다.',
          description: '새 공고가 동기화되면 검색 결과에 표시됩니다.',
        }
      }
      return {
        title: '공고 검색이 가능합니다.',
        description: '현재 저장된 공고를 바로 검색할 수 있습니다.',
      }
    case 'SEARCHABLE_WITH_SYNC_FAILURE':
      return {
        title: '이전 공고 데이터로 검색할 수 있습니다.',
        description: '최신 공고 동기화에 실패했지만, 이전에 저장된 공고는 계속 검색할 수 있습니다.',
      }
    case 'UNAVAILABLE':
      return {
        title: '현재 공고 데이터를 검색할 수 없습니다.',
        description: '잠시 후 상태를 다시 확인해 주세요.',
      }
  }
}

function formatSyncTime(value: string | null) {
  if (!value) return '기록 없음'

  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return value

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(date)
}

function ProgramCard({ program }: { program: SupportProgram }) {
  return (
    <article className={chatPageStyles.programCard}>
      <div className={chatPageStyles.programCardHeader}>
        <span className={chatPageStyles.programTag}>
          {program.recommendationScore === null
            ? '최신 공고'
            : `AI 추천 ${program.recommendationScore}점`}
        </span>
        <span className={chatPageStyles.programDeadline}>
          {formatApplicationDeadline(program)}
        </span>
      </div>
      <h2 className={chatPageStyles.programTitle}>
        {program.title}
      </h2>
      <p className={chatPageStyles.programOrganization}>{program.organization}</p>
      <p className={chatPageStyles.programSummary}>{program.summary}</p>
      <div className={chatPageStyles.programDetails}>
        <span>{program.targetDescription}</span>
      </div>
      <div className={chatPageStyles.matchedReasons}>
        {program.matchedReasons.map((reason) => (
          <span key={reason} className={chatPageStyles.matchedReason}>
            ✓ {reason}
          </span>
        ))}
      </div>
      <div className={chatPageStyles.programActions}>
        <Link
          className={chatPageStyles.programDetailsButton}
          to={createSupportProgramDetailPath(program)}
        >
          상세 조건 보기
        </Link>
        <a
          href={program.sourceUrl}
          target="_blank"
          rel="noreferrer"
          className={chatPageStyles.programSourceLink}
        >
          원문 보기 ↗
        </a>
      </div>
    </article>
  )
}

function createSupportProgramDetailPath(program: SupportProgram) {
  const searchParams = new URLSearchParams({
    sourceCode: program.sourceCode,
    sourceProgramId: program.id,
  })
  return `/support-programs/detail?${searchParams.toString()}`
}

function formatApplicationDeadline(program: SupportProgram) {
  if (!program.applicationEndDate) return program.applicationPeriod

  const [, month, day] = program.applicationEndDate.split('-')
  return `마감 ${Number(month)}월 ${Number(day)}일`
}

const sidebarFocusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

function focusFirstSidebarElement(sidebar: HTMLElement) {
  const [firstElement] = getSidebarFocusableElements(sidebar)
  if (firstElement) {
    firstElement.focus()
    return
  }
  sidebar.focus()
}

function getSidebarFocusableElements(sidebar: HTMLElement): HTMLElement[] {
  return Array.from(sidebar.querySelectorAll<HTMLElement>(sidebarFocusableSelector))
    .filter((element) => element.getAttribute('aria-hidden') !== 'true')
}
