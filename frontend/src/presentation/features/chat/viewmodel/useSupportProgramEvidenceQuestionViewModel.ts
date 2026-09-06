import { useEffect, useRef, useState } from 'react'

import { appContainer } from '../../../../app/appContainer'
import type { SupportProgramEvidenceAnswer } from '../../../../domain/entities/SupportProgramEvidenceAnswer'
import type { SupportProgramIdentity } from '../../../../domain/repositories/SupportProgramRepository'
import type { AskSupportProgramEvidenceQuestionUseCase } from '../../../../domain/usecases/AskSupportProgramEvidenceQuestionUseCase'
import { SupportProgramRequestError } from '../../../../domain/errors/SupportProgramRequestError'
import { supportProgramRequestFailureMessage } from './supportProgramRequestFailureMessage'

export const maximumSupportProgramEvidenceQuestionLength = 500

type SupportProgramEvidenceQuestionUseCase = Pick<
  AskSupportProgramEvidenceQuestionUseCase,
  'execute'
>

export type SupportProgramEvidenceQuestionState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'answered'; answer: SupportProgramEvidenceAnswer }
  | { status: 'insufficient-evidence' }
  | { status: 'not-supported' }
  | { status: 'unavailable' }
  | { status: 'failed' }
  | { status: 'rate-limited' | 'busy'; message: string }
  | { status: 'cancelled' }
  | { status: 'validation-failed'; message: string }

/** 상세 화면에서 사용자가 명시적으로 요청한 원문 근거 질문만 실행합니다. */
export function useSupportProgramEvidenceQuestionViewModel(
  identity: SupportProgramIdentity,
  askSupportProgramEvidenceQuestionUseCase: SupportProgramEvidenceQuestionUseCase = appContainer.resolve(
    'askSupportProgramEvidenceQuestionUseCase',
  ),
) {
  const { sourceCode, sourceProgramId } = identity
  const [question, setQuestion] = useState('')
  const [state, setState] = useState<SupportProgramEvidenceQuestionState>({ status: 'idle' })
  const activeRequest = useRef<{
    controller: AbortController
    requestId: number
  } | null>(null)
  const latestRequestId = useRef(0)
  const questionLength = question.length
  const isSupported = sourceCode === 'BIZINFO'
  const isAnswering = state.status === 'loading'
  const canSubmit = isSupported && !isAnswering
    && question.trim().length > 0
    && questionLength <= maximumSupportProgramEvidenceQuestionLength

  useEffect(() => {
    setQuestion('')
    setState({ status: 'idle' })

    return () => {
      const currentRequest = activeRequest.current
      activeRequest.current = null
      currentRequest?.controller.abort()
    }
  }, [sourceCode, sourceProgramId])

  function updateQuestion(value: string) {
    if (isAnswering) return

    setQuestion(value)
    setState({ status: 'idle' })
  }

  function cancelQuestion() {
    const currentRequest = activeRequest.current
    activeRequest.current = null
    if (!currentRequest) return

    currentRequest.controller.abort()
    setState({ status: 'cancelled' })
  }

  async function submitQuestion(): Promise<void> {
    if (!isSupported) {
      setState({ status: 'not-supported' })
      return
    }
    const normalizedQuestion = question.trim()
    if (normalizedQuestion.length === 0) {
      setState({
        status: 'validation-failed',
        message: '질문을 입력해 주세요.',
      })
      return
    }
    if (questionLength > maximumSupportProgramEvidenceQuestionLength) {
      setState({
        status: 'validation-failed',
        message: `질문은 ${maximumSupportProgramEvidenceQuestionLength}자 이하로 입력해 주세요. 현재 ${questionLength}자입니다.`,
      })
      return
    }
    if (activeRequest.current) return

    const controller = new AbortController()
    const requestId = latestRequestId.current + 1
    latestRequestId.current = requestId
    activeRequest.current = { controller, requestId }
    setState({ status: 'loading' })

    try {
      const result = await askSupportProgramEvidenceQuestionUseCase.execute(
        { sourceCode, sourceProgramId, question: normalizedQuestion },
        controller.signal,
      )
      if (controller.signal.aborted || activeRequest.current?.requestId !== requestId) return

      if (result.outcome === 'answer') {
        setState(result.answer.answerStatus === 'ANSWERED'
          ? { status: 'answered', answer: result.answer }
          : { status: 'insufficient-evidence' })
        return
      }

      setState(result.outcome === 'not-supported'
        ? { status: 'not-supported' }
        : { status: 'unavailable' })
    } catch (error) {
      if (controller.signal.aborted || activeRequest.current?.requestId !== requestId) return
      setState(error instanceof SupportProgramRequestError
        ? { status: error.reason, message: supportProgramRequestFailureMessage(error) }
        : { status: 'failed' })
    } finally {
      if (activeRequest.current?.requestId === requestId) {
        activeRequest.current = null
      }
    }
  }

  return {
    canSubmit,
    cancelQuestion,
    isAnswering,
    isSupported,
    question,
    questionLength,
    state,
    submitQuestion,
    updateQuestion,
  }
}
