import { useEffect, useRef } from 'react'

import { appContainer } from '../../../../app/appContainer'
import { useAppDispatch, useAppSelector } from '../../../../app/hooks'
import type { AppDispatch, RootState } from '../../../../app/store'
import type { SearchSupportProgramsUseCase } from '../../../../domain/usecases/SearchSupportProgramsUseCase'
import {
  conversationReset,
  draftChanged,
  maximumSupportProgramSearchQueryLength,
  searchCancelled,
  searchFailed,
  searchStarted,
  searchSucceeded,
  searchTimedOut,
  searchValidationFailed,
  selectCanRetryChatSearch,
  selectChatDraft,
  selectChatMessages,
  selectChatSearchError,
  selectChatState,
  selectConversationCount,
  selectIsChatSearching,
  selectIsReadyToSubmit,
} from '../state/chatSlice'

export const supportProgramChatSuggestions = [
  '서울 AI 창업지원 사업 찾아줘',
  '현재 접수 중인 수출 지원사업 알려줘',
  '제조기업 R&D 사업을 찾아줘',
]

/** 순차 의미 검색(30초)·점수화(35초)에 여유를 두고 검색 요청 시간을 제한합니다. */
export const supportProgramSearchTimeoutMilliseconds = 70_000

type SupportProgramSearchUseCase = Pick<SearchSupportProgramsUseCase, 'execute'>

export function useSupportProgramChatViewModel(
  searchSupportProgramsUseCase: SupportProgramSearchUseCase = appContainer.resolve('searchSupportProgramsUseCase')
) {
  const dispatchToStore = useAppDispatch()
  const activeSearchRequest = useRef<{
    controller: AbortController
    query: string
    requestId: string
    timeoutId: ReturnType<typeof setTimeout>
  } | null>(null)
  const conversationCount = useAppSelector(selectConversationCount)
  const draft = useAppSelector(selectChatDraft)
  const isReadyToSubmit = useAppSelector(selectIsReadyToSubmit)
  const isSearching = useAppSelector(selectIsChatSearching)
  const messages = useAppSelector(selectChatMessages)
  const canRetrySearch = useAppSelector(selectCanRetryChatSearch)
  const searchError = useAppSelector(selectChatSearchError)

  useEffect(() => () => {
    const currentRequest = activeSearchRequest.current
    activeSearchRequest.current = null
    if (!currentRequest) return

    clearTimeout(currentRequest.timeoutId)
    currentRequest.controller.abort()
    dispatchToStore(searchCancelled({
      query: currentRequest.query,
      requestId: currentRequest.requestId,
    }))
  }, [dispatchToStore])

  function startNewConversation() {
    const currentRequest = activeSearchRequest.current
    activeSearchRequest.current = null
    if (currentRequest) {
      clearTimeout(currentRequest.timeoutId)
      currentRequest.controller.abort()
    }
    dispatchToStore(conversationReset())
  }

  function cancelSearch() {
    const currentRequest = activeSearchRequest.current
    activeSearchRequest.current = null
    if (!currentRequest) return

    clearTimeout(currentRequest.timeoutId)
    currentRequest.controller.abort()
    dispatchToStore(searchCancelled({
      query: currentRequest.query,
      requestId: currentRequest.requestId,
    }))
  }

  function selectSuggestion(suggestion: string) {
    dispatchToStore(draftChanged(suggestion))
  }

  function updateDraft(value: string) {
    dispatchToStore(draftChanged(value))
  }

  function submitMessage() {
    async function runSupportProgramSearch(
      dispatchAction: AppDispatch,
      readCurrentState: () => RootState,
    ): Promise<void> {
      const currentState = readCurrentState()
      const currentChatState = selectChatState(currentState)
      const searchQuery = currentChatState.draft.trim()

      if (searchQuery.length === 0) return
      if (currentChatState.searchStatus === 'pending') return
      if (searchQuery.length > maximumSupportProgramSearchQueryLength) {
        dispatchAction(searchValidationFailed({ queryLength: searchQuery.length }))
        return
      }

      const searchStartedAction = searchStarted(searchQuery)
      const requestController = new AbortController()
      const requestId = searchStartedAction.payload.requestId

      dispatchAction(searchStartedAction)
      const timeoutId = setTimeout(() => {
        if (activeSearchRequest.current?.requestId !== requestId) return

        activeSearchRequest.current = null
        dispatchAction(searchTimedOut({ query: searchQuery, requestId }))
        requestController.abort()
      }, supportProgramSearchTimeoutMilliseconds)
      activeSearchRequest.current = {
        controller: requestController,
        query: searchQuery,
        requestId,
        timeoutId,
      }

      try {
        const searchResult = await searchSupportProgramsUseCase.execute(
          searchQuery,
          requestController.signal,
        )

        if (requestController.signal.aborted) return

        const searchSucceededAction = searchSucceeded({
          programs: searchResult.programs,
          requestId,
        })
        dispatchAction(searchSucceededAction)
      } catch {
        if (requestController.signal.aborted) return

        const searchFailedAction = searchFailed({ query: searchQuery, requestId })
        dispatchAction(searchFailedAction)
      } finally {
        const currentRequest = activeSearchRequest.current
        if (currentRequest?.requestId === requestId) {
          clearTimeout(currentRequest.timeoutId)
          activeSearchRequest.current = null
        }
      }
    }

    return dispatchToStore(runSupportProgramSearch)
  }

  return {
    conversationCount,
    canRetrySearch,
    draft,
    isReadyToSubmit,
    isSearching,
    messages,
    cancelSearch,
    searchError,
    selectSuggestion,
    startNewConversation,
    submitMessage,
    updateDraft,
  }
}
