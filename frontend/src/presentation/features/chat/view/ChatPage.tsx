import { type FormEvent, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'

import type { SupportProgram } from '../../../../domain/entities/SupportProgram'
import {
  supportProgramChatSuggestions,
  useSupportProgramChatViewModel,
} from '../viewmodel/useSupportProgramChatViewModel'
import {
  chatBackdropClassName,
  chatMessageBubbleClassName,
  chatMessageRowClassName,
  chatPageStyles,
  chatSidebarClassName,
} from './ChatPage.styles'

export function ChatPage() {
  const {
    conversationCount,
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
  const timelineRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const timeline = timelineRef.current
    if (timeline) timeline.scrollTop = timeline.scrollHeight
  }, [messages, isSearching])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void submitMessage()
  }

  function handleStartNewConversation() {
    startNewConversation()
    setIsSidebarOpen(false)
  }

  function handleSelectSuggestion(suggestion: string) {
    selectSuggestion(suggestion)
    setIsSidebarOpen(false)
  }

  return (
    <main className={chatPageStyles.page}>
      <button
        type="button"
        className={chatBackdropClassName(isSidebarOpen)}
        onClick={() => setIsSidebarOpen(false)}
      />

      <aside
        className={chatSidebarClassName(isSidebarOpen)}
      >
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
            인기 질문
          </p>
          {supportProgramChatSuggestions.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              className={chatPageStyles.popularQuestionButton}
              onClick={() => handleSelectSuggestion(suggestion)}
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
            <strong className={chatPageStyles.dataSummaryValue}>공식</strong>
            <span className={chatPageStyles.dataSummaryLabel}>기업마당</span>
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

      <section className={chatPageStyles.workspace}>
        <header className={chatPageStyles.header}>
          <button
            type="button"
            className={chatPageStyles.menuButton}
            onClick={() => setIsSidebarOpen(true)}
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
                        >
                          {suggestion}
                        </button>
                      ))}
                    </div>
                  ) : null}
                  {message.programs?.length ? (
                    <div className={chatPageStyles.programList}>
                      {message.programs.map((program) => (
                        <ProgramCard key={program.id} program={program} />
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
          {searchError ? (
            <p className={chatPageStyles.searchError}>
              {searchError}
            </p>
          ) : null}
          <textarea
            className={chatPageStyles.composerInput}
            value={draft}
            onChange={(event) => updateDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                event.currentTarget.form?.requestSubmit()
              }
            }}
            placeholder="예: 서울에서 AI 창업지원 사업을 찾아줘"
            rows={1}
          />
          <button
            type="submit"
            className={chatPageStyles.submitButton}
            disabled={!isReadyToSubmit}
          >
            ↑
          </button>
          <small className={chatPageStyles.composerHint}>
            Enter로 전송 · Shift+Enter로 줄바꿈
          </small>
        </form>
      </section>
    </main>
  )
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
        <button
          type="button"
          className={chatPageStyles.programDetailsButton}
          onClick={() => window.alert('상세 화면은 다음 단계에서 연결됩니다.')}
        >
          상세 조건 보기
        </button>
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

function formatApplicationDeadline(program: SupportProgram) {
  if (!program.applicationEndDate) return program.applicationPeriod

  const [, month, day] = program.applicationEndDate.split('-')
  return `마감 ${Number(month)}월 ${Number(day)}일`
}
