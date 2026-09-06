// @vitest-environment jsdom

import {
  createElement,
  type ComponentType,
  type PropsWithChildren,
  type ReactNode,
} from 'react'
import { Provider } from 'react-redux'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { createAppStore } from '../../../../app/store'
import { supportPrograms } from '../../../../data/fixtures/supportPrograms'
import { SupportProgramRequestError } from '../../../../domain/errors/SupportProgramRequestError'
import type { SearchSupportProgramsUseCase } from '../../../../domain/usecases/SearchSupportProgramsUseCase'
import {
  draftChanged,
  maximumSupportProgramSearchQueryLength,
} from '../state/chatSlice'
import { useSupportProgramChatViewModel } from './useSupportProgramChatViewModel'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('Redux chat flow', () => {
  it('stores the user message and injected search service result in the chat slice', async () => {
    const execute = vi.fn().mockResolvedValue({
      query: '서울 AI',
      programs: [supportPrograms[0]],
    })
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('서울 AI')))
    await act(async () => result.current.submitMessage())

    const chat = store.getState().chat
    expect(execute).toHaveBeenCalledWith('서울 AI', expect.any(AbortSignal))
    expect(chat.searchStatus).toBe('idle')
    expect(chat.messages.map((message) => message.role)).toEqual([
      'assistant',
      'user',
      'assistant',
    ])
    expect(chat.messages.at(-1)?.programs?.[0]?.id).toBe('fixture-seoul-ai-business')
  })

  it('does not start a duplicate search while the first request is pending', async () => {
    const pending = deferredSearchResult()
    const execute = vi.fn().mockReturnValue(pending.promise)
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('수출')))
    let firstSearch!: Promise<void>
    let duplicateSearch!: Promise<void>
    act(() => {
      firstSearch = result.current.submitMessage()
      duplicateSearch = result.current.submitMessage()
    })
    pending.resolve({ query: '수출', programs: [supportPrograms[3]] })
    await act(async () => Promise.all([firstSearch, duplicateSearch]))

    expect(execute).toHaveBeenCalledOnce()
    expect(store.getState().chat.messages).toHaveLength(3)
  })

  it('aborts and ignores a pending result when a new conversation starts', async () => {
    const pending = deferredSearchResult()
    let requestSignal: AbortSignal | undefined
    const execute = vi.fn((_query: string, signal?: AbortSignal) => {
      requestSignal = signal
      return pending.promise
    })
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('제조')))
    let search!: Promise<void>
    act(() => {
      search = result.current.submitMessage()
    })
    await waitFor(() => expect(execute).toHaveBeenCalledOnce())
    act(() => result.current.startNewConversation())

    expect(requestSignal?.aborted).toBe(true)
    pending.resolve({ query: '제조', programs: [supportPrograms[2]] })
    await act(async () => search)

    const chat = store.getState().chat
    expect(chat.searchStatus).toBe('idle')
    expect(chat.messages).toHaveLength(1)
  })

  it('keeps a new draft but blocks resubmission while a request is pending', async () => {
    const firstPending = deferredSearchResult()
    let firstSignal: AbortSignal | undefined
    const execute = vi.fn((_query: string, signal?: AbortSignal) => {
      firstSignal = signal
      return firstPending.promise
    })
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('서울')))
    let firstSearch!: Promise<void>
    act(() => {
      firstSearch = result.current.submitMessage()
    })
    await waitFor(() => expect(execute).toHaveBeenCalledOnce())

    act(() => store.dispatch(draftChanged('수출')))
    await waitFor(() => expect(result.current.isReadyToSubmit).toBe(false))
    await act(async () => result.current.submitMessage())
    expect(firstSignal?.aborted).toBe(false)
    expect(execute).toHaveBeenCalledOnce()

    firstPending.resolve({ query: '서울', programs: [supportPrograms[0]] })
    await act(async () => firstSearch)

    const chat = store.getState().chat
    expect(chat.searchStatus).toBe('idle')
    expect(chat.draft).toBe('수출')
    expect(chat.messages.at(-1)?.programs?.[0]?.id).toBe('fixture-seoul-ai-business')
  })

  it('500자를 넘는 검색어는 요청하지 않고 입력값과 검증 메시지를 유지한다', async () => {
    const execute = vi.fn()
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))
    const overlongQuery = '가'.repeat(maximumSupportProgramSearchQueryLength + 1)

    act(() => store.dispatch(draftChanged(overlongQuery)))
    await act(async () => result.current.submitMessage())

    const chat = store.getState().chat
    expect(execute).not.toHaveBeenCalled()
    expect(chat.draft).toBe(overlongQuery)
    expect(chat.messages).toHaveLength(1)
    expect(chat.searchStatus).toBe('idle')
    expect(chat.searchError).toBe('검색어는 500자 이하로 입력해 주세요. 현재 501자입니다.')
  })

  it('stores a safe error when the search service fails', async () => {
    const execute = vi.fn().mockRejectedValue(new Error('private server detail'))
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('서울')))
    await act(async () => result.current.submitMessage())

    const chat = store.getState().chat
    expect(chat.searchStatus).toBe('failed')
    expect(chat.draft).toBe('서울')
    expect(chat.searchError).toBe('지원사업을 검색하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    expect(chat.searchError).not.toContain('private server detail')
  })

  it.each([
    ['rate-limited', 12, '짧은 시간에 요청이 많아 잠시 제한되었습니다. 약 12초 후 직접 다시 시도해 주세요.'],
    ['busy', 3, '현재 다른 요청을 처리하고 있어 새 요청을 시작할 수 없습니다. 약 3초 후 직접 다시 시도해 주세요.'],
    ['rate-limited', null, '짧은 시간에 요청이 많아 잠시 제한되었습니다. 잠시 후 직접 다시 시도해 주세요.'],
  ] as const)('keeps the conversation and query for manual retry after %s', async (reason, seconds, message) => {
    vi.useFakeTimers()
    const execute = vi.fn()
      .mockResolvedValueOnce({ query: '서울', programs: [supportPrograms[0]] })
      .mockRejectedValueOnce(new SupportProgramRequestError(reason, seconds))
      .mockResolvedValueOnce({ query: '수출', programs: [supportPrograms[3]] })
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))
    act(() => result.current.updateDraft('서울'))
    await act(async () => result.current.submitMessage())
    const priorMessages = store.getState().chat.messages

    act(() => result.current.updateDraft('수출'))
    await act(async () => result.current.submitMessage())
    expect(result.current.searchError).toBe(message)
    expect(result.current.draft).toBe('수출')
    expect(result.current.canRetrySearch).toBe(true)
    expect(store.getState().chat.messages.slice(0, 3)).toEqual(priorMessages)
    expect(store.getState().chat.messages.at(-1)?.text).toBe('수출')

    await act(async () => vi.advanceTimersByTimeAsync(60_000))
    expect(execute).toHaveBeenCalledTimes(2)
    await act(async () => result.current.submitMessage())
    expect(execute).toHaveBeenCalledTimes(3)
    expect(store.getState().chat.messages.slice(0, 3)).toEqual(priorMessages)
    expect(result.current.searchError).toBeNull()
  })

  it('cancels a pending search, restores its query, and clears the pending state', async () => {
    const pending = deferredSearchResult()
    let requestSignal: AbortSignal | undefined
    const execute = vi.fn((_query: string, signal?: AbortSignal) => {
      requestSignal = signal
      return pending.promise
    })
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('수출')))
    let search!: Promise<void>
    act(() => {
      search = result.current.submitMessage()
    })
    await waitFor(() => expect(execute).toHaveBeenCalledOnce())

    act(() => result.current.cancelSearch())

    expect(requestSignal?.aborted).toBe(true)
    expect(store.getState().chat.searchStatus).toBe('idle')
    expect(store.getState().chat.draft).toBe('수출')
    expect(store.getState().chat.searchError).toBeNull()

    pending.resolve({ query: '수출', programs: [supportPrograms[3]] })
    await search
    expect(store.getState().chat.messages).toHaveLength(2)
  })

  it('waits past 30 seconds, cancels at 70 seconds, and allows retry while ignoring the old result', async () => {
    vi.useFakeTimers()
    const pending = deferredSearchResult()
    let requestSignal: AbortSignal | undefined
    const execute = vi.fn((_query: string, signal?: AbortSignal) => {
      requestSignal = signal
      return pending.promise
    })
    const store = createAppStore()
    const { result } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('창업')))
    let search!: Promise<void>
    act(() => {
      search = result.current.submitMessage()
    })
    expect(execute).toHaveBeenCalledOnce()

    act(() => {
      vi.advanceTimersByTime(30_000)
    })
    expect(requestSignal?.aborted).toBe(false)
    expect(store.getState().chat.searchStatus).toBe('pending')

    act(() => {
      vi.advanceTimersByTime(39_999)
    })
    expect(requestSignal?.aborted).toBe(false)
    expect(store.getState().chat.searchStatus).toBe('pending')

    act(() => {
      vi.advanceTimersByTime(1)
    })

    const chat = store.getState().chat
    expect(requestSignal?.aborted).toBe(true)
    expect(chat.searchStatus).toBe('failed')
    expect(chat.draft).toBe('창업')
    expect(chat.searchError).toBe('검색 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.')
    expect(result.current.canRetrySearch).toBe(true)

    execute.mockResolvedValueOnce({ query: '창업', programs: [supportPrograms[0]] })
    await act(async () => result.current.submitMessage())
    expect(execute).toHaveBeenCalledTimes(2)
    expect(execute.mock.calls[1][0]).toBe('창업')
    expect(execute.mock.calls[1][1]?.aborted).toBe(false)
    expect(store.getState().chat.searchStatus).toBe('idle')
    expect(store.getState().chat.searchError).toBeNull()
    expect(store.getState().chat.messages.at(-1)?.programs?.[0]?.id).toBe('fixture-seoul-ai-business')

    pending.resolve({ query: '창업', programs: [supportPrograms[1]] })
    await search
    expect(store.getState().chat.messages).toHaveLength(4)
    expect(store.getState().chat.messages.at(-1)?.programs?.[0]?.id).toBe('fixture-seoul-ai-business')
  })

  it('aborts a pending request and clears pending state on unmount', async () => {
    const pending = deferredSearchResult()
    let requestSignal: AbortSignal | undefined
    const execute = vi.fn((_query: string, signal?: AbortSignal) => {
      requestSignal = signal
      return pending.promise
    })
    const store = createAppStore()
    const { result, unmount } = renderChatViewModel(store, createSearchUseCase(execute))

    act(() => store.dispatch(draftChanged('창업')))
    let search!: Promise<void>
    act(() => {
      search = result.current.submitMessage()
    })
    await waitFor(() => expect(execute).toHaveBeenCalledOnce())

    unmount()
    expect(requestSignal?.aborted).toBe(true)
    expect(store.getState().chat.searchStatus).toBe('idle')

    pending.resolve({ query: '창업', programs: [supportPrograms[1]] })
    await search
    expect(store.getState().chat.messages).toHaveLength(2)
  })
})

function renderChatViewModel(
  store: ReturnType<typeof createAppStore>,
  searchUseCase: Pick<SearchSupportProgramsUseCase, 'execute'>,
) {
  return renderHook(() => useSupportProgramChatViewModel(searchUseCase), {
    wrapper: createWrapper(store),
  })
}

function createWrapper(store: ReturnType<typeof createAppStore>) {
  const StoreProvider = Provider as unknown as ComponentType<PropsWithChildren<{ store: typeof store }>>

  return function TestWrapper({ children }: { children: ReactNode }) {
    return createElement(StoreProvider, { store }, children)
  }
}

function createSearchUseCase(
  execute: SearchSupportProgramsUseCase['execute'],
): Pick<SearchSupportProgramsUseCase, 'execute'> {
  return { execute }
}

function deferredSearchResult() {
  type Result = Awaited<ReturnType<SearchSupportProgramsUseCase['execute']>>
  let resolve!: (result: Result) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<Result>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, reject, resolve }
}
