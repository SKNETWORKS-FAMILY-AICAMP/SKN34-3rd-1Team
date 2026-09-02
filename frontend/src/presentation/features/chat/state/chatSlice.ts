import { createSelector, createSlice, nanoid, type PayloadAction } from '@reduxjs/toolkit'

import type { RootState } from '../../../../app/store'
import type { SupportProgram } from '../../../../domain/entities/SupportProgram'

export type SupportProgramChatMessage = {
  id: string
  role: 'assistant' | 'user'
  text: string
  programs?: SupportProgram[]
}

type ChatSearchStatus = 'idle' | 'pending' | 'failed'

type ChatState = {
  activeRequestId: string | null
  draft: string
  messages: SupportProgramChatMessage[]
  searchError: string | null
  searchStatus: ChatSearchStatus
}

const initialState: ChatState = createInitialState()

const chatSlice = createSlice({
  name: 'chat',
  initialState,
  reducers: {
    conversationReset: {
      reducer(_state, action: PayloadAction<{ welcomeMessage: SupportProgramChatMessage }>) {
        return createInitialState(action.payload.welcomeMessage)
      },
      prepare() {
        return { payload: { welcomeMessage: createWelcomeMessage() } }
      },
    },
    draftChanged(state, action: PayloadAction<string>) {
      state.draft = action.payload
      state.searchError = null
    },
    searchCancelled(state, action: PayloadAction<{ requestId: string }>) {
      if (state.activeRequestId !== action.payload.requestId) return
      state.activeRequestId = null
      state.searchError = null
      state.searchStatus = 'idle'
    },
    searchFailed(state, action: PayloadAction<{ requestId: string }>) {
      if (state.activeRequestId !== action.payload.requestId) return
      state.activeRequestId = null
      state.searchError = '지원사업을 검색하지 못했습니다. 잠시 후 다시 시도해 주세요.'
      state.searchStatus = 'failed'
    },
    searchStarted: {
      reducer(
        state,
        action: PayloadAction<{ messageId: string; query: string; requestId: string }>,
      ) {
        if (state.searchStatus === 'pending') return
        state.activeRequestId = action.payload.requestId
        state.draft = ''
        state.messages.push({
          id: action.payload.messageId,
          role: 'user',
          text: action.payload.query,
        })
        state.searchError = null
        state.searchStatus = 'pending'
      },
      prepare(query: string) {
        return {
          payload: {
            messageId: nanoid(),
            query,
            requestId: nanoid(),
          },
        }
      },
    },
    searchSucceeded: {
      reducer(
        state,
        action: PayloadAction<{
          messageId: string
          programs: SupportProgram[]
          requestId: string
        }>,
      ) {
        if (state.activeRequestId !== action.payload.requestId) return
        state.activeRequestId = null
        state.messages.push({
          id: action.payload.messageId,
          role: 'assistant',
          text: createSearchResponseText(action.payload.programs.length),
          programs: action.payload.programs,
        })
        state.searchError = null
        state.searchStatus = 'idle'
      },
      prepare(payload: { programs: SupportProgram[]; requestId: string }) {
        return {
          payload: {
            ...payload,
            messageId: nanoid(),
          },
        }
      },
    },
  },
})

export const {
  conversationReset,
  draftChanged,
  searchCancelled,
  searchFailed,
  searchStarted,
  searchSucceeded,
} = chatSlice.actions

export const selectChatState = (state: RootState) => state.chat
export const selectChatDraft = (state: RootState) => state.chat.draft
export const selectChatMessages = (state: RootState) => state.chat.messages
export const selectChatSearchError = (state: RootState) => state.chat.searchError
export const selectIsChatSearching = (state: RootState) => state.chat.searchStatus === 'pending'
export const selectConversationCount = createSelector(
  [selectChatMessages],
  (messages) => messages.filter((message) => message.role === 'user').length,
)
export const selectIsReadyToSubmit = createSelector(
  [selectChatDraft, selectIsChatSearching],
  (draft, isSearching) => draft.trim().length > 0 && !isSearching,
)

export default chatSlice.reducer

function createInitialState(welcomeMessage = createWelcomeMessage()): ChatState {
  return {
    activeRequestId: null,
    draft: '',
    messages: [welcomeMessage],
    searchError: null,
    searchStatus: 'idle',
  }
}

function createWelcomeMessage(): SupportProgramChatMessage {
  return {
    id: nanoid(),
    role: 'assistant',
    text: '안녕하세요. GovBiz가 현재 접수 중인 정부지원사업을 찾아드릴게요. 지역이나 업종을 포함해 편하게 말씀해 주세요.',
  }
}

function createSearchResponseText(programCount: number) {
  return programCount > 0
    ? `현재 접수 중인 관련 공고 ${programCount}건을 찾았습니다. 공고를 선택하면 자세한 조건과 원문을 확인할 수 있어요.`
    : '현재 일치하는 공고를 찾지 못했습니다. 지역이나 분야를 바꿔 다시 검색해 보세요.'
}
