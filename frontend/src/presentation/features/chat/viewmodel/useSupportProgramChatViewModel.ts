import { useEffect, useRef } from 'react'

import { appContainer } from '../../../../app/appContainer'
import { useAppDispatch, useAppSelector } from '../../../../app/hooks'
import type { AppDispatch, RootState } from '../../../../app/store'
import type { SearchSupportProgramsUseCase } from '../../../../domain/usecases/SearchSupportProgramsUseCase'
import {
  conversationReset,
  draftChanged,
  searchCancelled,
  searchFailed,
  searchStarted,
  searchSucceeded,
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

type SupportProgramSearchUseCase = Pick<SearchSupportProgramsUseCase, 'execute'>

export function useSupportProgramChatViewModel(
  searchSupportProgramsUseCase: SupportProgramSearchUseCase = appContainer.resolve('searchSupportProgramsUseCase')
) {
  const dispatchToStore = useAppDispatch()
  const activeSearchRequest = useRef<{
    controller: AbortController
    requestId: string
  } | null>(null)
  const conversationCount = useAppSelector(selectConversationCount)
  const draft = useAppSelector(selectChatDraft)
  const isReadyToSubmit = useAppSelector(selectIsReadyToSubmit)
  const isSearching = useAppSelector(selectIsChatSearching)
  const messages = useAppSelector(selectChatMessages)
  const searchError = useAppSelector(selectChatSearchError)

  useEffect(() => () => {
    const currentRequest = activeSearchRequest.current
    activeSearchRequest.current = null
    if (!currentRequest) return

    currentRequest.controller.abort()
    dispatchToStore(searchCancelled({ requestId: currentRequest.requestId }))
  }, [dispatchToStore])

  function startNewConversation() {
    const currentRequest = activeSearchRequest.current
    activeSearchRequest.current = null
    currentRequest?.controller.abort()
    dispatchToStore(conversationReset())
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

      const searchStartedAction = searchStarted(searchQuery)
      const requestController = new AbortController()
      const requestId = searchStartedAction.payload.requestId

      dispatchAction(searchStartedAction)
      activeSearchRequest.current = {
        controller: requestController,
        requestId,
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

        const searchFailedAction = searchFailed({ requestId })
        dispatchAction(searchFailedAction)
      } finally {
        const isCurrentRequest = activeSearchRequest.current?.requestId === requestId
        if (isCurrentRequest) {
          activeSearchRequest.current = null
        }
      }
    }

    return dispatchToStore(runSupportProgramSearch)
  }

  return {
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
  }
}
