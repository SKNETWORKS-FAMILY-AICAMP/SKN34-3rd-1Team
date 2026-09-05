import type { FormEvent } from 'react'

import type { SupportProgramIdentity } from '../../../../domain/repositories/SupportProgramRepository'
import {
  maximumSupportProgramEvidenceQuestionLength,
  useSupportProgramEvidenceQuestionViewModel,
} from '../viewmodel/useSupportProgramEvidenceQuestionViewModel'
import { supportProgramDetailStyles } from './SupportProgramDetailPage.styles'

/** 사용자가 요청할 때만 특정 공고 원문을 근거로 질문을 보내는 상세 화면 영역입니다. */
export function SupportProgramEvidenceQuestionSection({
  identity,
}: {
  identity: SupportProgramIdentity
}) {
  const {
    canSubmit,
    cancelQuestion,
    isAnswering,
    question,
    questionLength,
    state,
    submitQuestion,
    updateQuestion,
  } = useSupportProgramEvidenceQuestionViewModel(identity)
  const isValidationFailed = state.status === 'validation-failed'

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void submitQuestion()
  }

  return (
    <section className={supportProgramDetailStyles.evidenceSection} aria-labelledby="evidence-question-title">
      <div className={supportProgramDetailStyles.evidenceHeader}>
        <div>
          <p className={supportProgramDetailStyles.sectionEyebrow}>공고 원문 기반</p>
          <h2 id="evidence-question-title" className={supportProgramDetailStyles.sectionTitle}>
            이 공고에 질문하기
          </h2>
        </div>
        <span className={supportProgramDetailStyles.evidenceBadge}>근거 답변</span>
      </div>
      <p className={supportProgramDetailStyles.evidenceDescription}>
        공고 원문에 있는 내용만 근거로 답합니다. 최종 신청 조건은 원문 공고에서 다시 확인해 주세요.
      </p>

      <form className={supportProgramDetailStyles.evidenceForm} onSubmit={handleSubmit}>
        <label className={supportProgramDetailStyles.evidenceLabel} htmlFor="support-program-evidence-question">
          공고 원문에 질문하기
        </label>
        <textarea
          id="support-program-evidence-question"
          className={supportProgramDetailStyles.evidenceInput}
          aria-describedby="support-program-evidence-question-hint support-program-evidence-question-count"
          aria-invalid={isValidationFailed}
          disabled={isAnswering}
          value={question}
          onChange={(event) => updateQuestion(event.target.value)}
          placeholder="예: 신청 대상과 제출해야 하는 서류를 알려줘"
          rows={3}
        />
        <div className={supportProgramDetailStyles.evidenceControls}>
          <span id="support-program-evidence-question-count" className={supportProgramDetailStyles.evidenceCount}>
            {questionLength} / {maximumSupportProgramEvidenceQuestionLength}자
          </span>
          {isAnswering ? (
            <button
              type="button"
              className={supportProgramDetailStyles.evidenceCancelButton}
              onClick={cancelQuestion}
            >
              질문 취소
            </button>
          ) : (
            <button
              type="submit"
              className={supportProgramDetailStyles.evidenceSubmitButton}
              disabled={!canSubmit}
            >
              질문하고 근거 받기
            </button>
          )}
        </div>
        <small id="support-program-evidence-question-hint" className={supportProgramDetailStyles.evidenceHint}>
          질문은 최대 {maximumSupportProgramEvidenceQuestionLength}자이며, 자동으로 전송되지 않습니다.
        </small>
      </form>

      <EvidenceQuestionFeedback state={state} />
    </section>
  )
}

function EvidenceQuestionFeedback({
  state,
}: {
  state: ReturnType<typeof useSupportProgramEvidenceQuestionViewModel>['state']
}) {
  if (state.status === 'idle') return null

  if (state.status === 'loading') {
    return (
      <p className={supportProgramDetailStyles.evidenceFeedback} role="status" aria-live="polite">
        공고 원문에서 답변 근거를 찾고 있습니다.
      </p>
    )
  }

  if (state.status === 'answered') {
    return (
      <article className={supportProgramDetailStyles.evidenceAnswer} aria-live="polite">
        <p className={supportProgramDetailStyles.evidenceAnswerEyebrow}>원문 근거 답변</p>
        <p className={supportProgramDetailStyles.evidenceAnswerText}>{state.answer.answer}</p>
        <h3 className={supportProgramDetailStyles.evidenceCitationTitle}>답변 근거</h3>
        <ol className={supportProgramDetailStyles.evidenceCitationList}>
          {state.answer.citations.map((citation, index) => (
            <li
              key={`${citation.chunkOrder}:${citation.sourceUrl}:${citation.excerpt}`}
              className={supportProgramDetailStyles.evidenceCitation}
            >
              <blockquote className={supportProgramDetailStyles.evidenceExcerpt}>
                {citation.excerpt}
              </blockquote>
              <a
                className={supportProgramDetailStyles.evidenceSourceLink}
                href={citation.sourceUrl}
                target="_blank"
                rel="noreferrer"
              >
                근거 {index + 1} 원문 보기 ↗
              </a>
            </li>
          ))}
        </ol>
      </article>
    )
  }

  if (state.status === 'validation-failed') {
    return <p className={supportProgramDetailStyles.evidenceError} role="alert">{state.message}</p>
  }

  const message = evidenceFeedbackMessage(state.status)
  const isFailure = state.status === 'failed' || state.status === 'unavailable'
  return (
    <p
      className={isFailure
        ? supportProgramDetailStyles.evidenceError
        : supportProgramDetailStyles.evidenceFeedback}
      role={isFailure ? 'alert' : 'status'}
      aria-live="polite"
    >
      {message}
    </p>
  )
}

function evidenceFeedbackMessage(
  status: Exclude<
    ReturnType<typeof useSupportProgramEvidenceQuestionViewModel>['state']['status'],
    'idle' | 'loading' | 'answered' | 'validation-failed'
  >,
) {
  const messages = {
    cancelled: '질문 요청을 취소했습니다.',
    'insufficient-evidence': '공고 원문에서 이 질문에 답할 만큼 충분한 근거를 찾지 못했습니다. 원문 공고를 확인해 주세요.',
    'not-supported': '이 제공처 공고는 아직 원문 근거 답변을 지원하지 않습니다. 원문 공고에서 확인해 주세요.',
    unavailable: '원문 근거 답변을 지금 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    failed: '질문에 답하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  } as const
  return messages[status]
}
