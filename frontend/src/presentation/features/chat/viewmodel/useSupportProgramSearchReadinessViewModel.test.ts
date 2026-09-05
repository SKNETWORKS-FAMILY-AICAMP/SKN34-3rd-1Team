// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { SupportProgramSearchReadiness } from '../../../../domain/entities/SupportProgramSearchReadiness'
import {
  supportProgramReadinessPollingMilliseconds,
  useSupportProgramSearchReadinessViewModel,
} from './useSupportProgramSearchReadinessViewModel'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('useSupportProgramSearchReadinessViewModel', () => {
  it('aborts an outdated request and only displays the newest readiness response', async () => {
    const first = deferred<SupportProgramSearchReadiness>()
    const second = deferred<SupportProgramSearchReadiness>()
    const signals: AbortSignal[] = []
    const execute = vi.fn((signal?: AbortSignal) => {
      signals.push(signal!)
      return signals.length === 1 ? first.promise : second.promise
    })
    const useCase = createReadinessUseCase(execute)
    const { result } = renderHook(() => useSupportProgramSearchReadinessViewModel(useCase))

    await waitFor(() => expect(execute).toHaveBeenCalledOnce())
    let refetch!: Promise<void>
    act(() => {
      refetch = result.current.refetch()
    })
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(2))
    expect(signals[0].aborted).toBe(true)

    const latest = readiness('SEARCHABLE')
    await act(async () => {
      second.resolve(latest)
      await Promise.all([second.promise, refetch])
    })
    expect(result.current.data).toEqual(latest)
    expect(result.current.canSearch).toBe(true)

    await act(async () => {
      first.resolve(readiness('UNAVAILABLE'))
      await first.promise
    })
    expect(result.current.data).toEqual(latest)
  })

  it('polls only while initial data preparation is in progress and enables search after it finishes', async () => {
    vi.useFakeTimers()
    const execute = vi.fn()
      .mockResolvedValueOnce(readiness('PREPARING'))
      .mockResolvedValueOnce(readiness('SEARCHABLE'))
    const useCase = createReadinessUseCase(execute)
    const { result } = renderHook(() => useSupportProgramSearchReadinessViewModel(useCase))

    await act(async () => {
      await Promise.resolve()
    })
    expect(result.current.data?.searchState).toBe('PREPARING')
    expect(result.current.canSearch).toBe(false)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(supportProgramReadinessPollingMilliseconds)
    })
    expect(result.current.data?.searchState).toBe('SEARCHABLE')
    expect(execute).toHaveBeenCalledTimes(2)
    expect(result.current.canSearch).toBe(true)
  })

  it('aborts the current request when the chat page unmounts', async () => {
    const pending = deferred<SupportProgramSearchReadiness>()
    let signal: AbortSignal | undefined
    const execute = vi.fn((requestSignal?: AbortSignal) => {
      signal = requestSignal
      return pending.promise
    })
    const useCase = createReadinessUseCase(execute)
    const { unmount } = renderHook(() => useSupportProgramSearchReadinessViewModel(useCase))

    await waitFor(() => expect(execute).toHaveBeenCalledOnce())
    unmount()
    expect(signal?.aborted).toBe(true)

    await act(async () => {
      pending.resolve(readiness('SEARCHABLE'))
      await pending.promise
    })
  })

  it('keeps search disabled and exposes a retryable safe error when readiness cannot be checked', async () => {
    const execute = vi.fn()
      .mockRejectedValueOnce(new Error('private upstream status'))
      .mockResolvedValueOnce(readiness('SEARCHABLE_WITH_SYNC_FAILURE'))
    const useCase = createReadinessUseCase(execute)
    const { result } = renderHook(() => useSupportProgramSearchReadinessViewModel(useCase))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.canSearch).toBe(false)

    await act(async () => {
      await result.current.refetch()
    })
    expect(result.current).toMatchObject({
      data: readiness('SEARCHABLE_WITH_SYNC_FAILURE'),
      canSearch: true,
      isError: false,
    })
  })
})

function readiness(
  searchState: SupportProgramSearchReadiness['searchState'],
): SupportProgramSearchReadiness {
  return {
    searchState,
    programCount: searchState === 'PREPARING' ? 0 : 12,
    indexReady: searchState !== 'PREPARING' && searchState !== 'UNAVAILABLE',
    lastSuccessfulSyncAt: searchState === 'PREPARING' ? null : '2026-09-05T09:00:00+09:00',
    lastFailedSyncAt: searchState === 'SEARCHABLE_WITH_SYNC_FAILURE'
      ? '2026-09-05T10:00:00+09:00'
      : null,
  }
}

function deferred<Result>() {
  let resolve!: (result: Result) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<Result>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, reject, resolve }
}

function createReadinessUseCase(
  execute: (signal?: AbortSignal) => Promise<SupportProgramSearchReadiness>,
) {
  return { execute }
}
