// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CoreApiHealth } from '../../../data/api/coreApiHealth'
import { useCoreApiHealth } from './useCoreApiHealth'

afterEach(cleanup)

describe('useCoreApiHealth', () => {
  it('aborts the first StrictMode request and only applies the latest response', async () => {
    const first = deferred<CoreApiHealth>()
    const second = deferred<CoreApiHealth>()
    const signals: AbortSignal[] = []
    const fetchCoreApiHealth = vi.fn((signal?: AbortSignal) => {
      signals.push(signal!)
      return signals.length === 1 ? first.promise : second.promise
    })
    const { result } = renderHook(() => useCoreApiHealth(fetchCoreApiHealth), {
      reactStrictMode: true,
    })

    await waitFor(() => expect(fetchCoreApiHealth).toHaveBeenCalledTimes(2))
    expect(signals[0].aborted).toBe(true)

    const latestHealth = { service: 'govbiz-core-api', status: 'up' }
    await act(async () => {
      second.resolve(latestHealth)
      await second.promise
    })
    await waitFor(() => expect(result.current.data).toEqual(latestHealth))

    await act(async () => {
      first.resolve({ service: 'stale-core-api', status: 'down' })
      await first.promise
    })
    expect(result.current.data).toEqual(latestHealth)
    expect(result.current.isError).toBe(false)
  })

  it('aborts the previous request on refetch and aborts the latest request on unmount', async () => {
    const first = deferred<CoreApiHealth>()
    const second = deferred<CoreApiHealth>()
    const signals: AbortSignal[] = []
    const fetchCoreApiHealth = vi.fn((signal?: AbortSignal) => {
      signals.push(signal!)
      return signals.length === 1 ? first.promise : second.promise
    })
    const { result, unmount } = renderHook(() => useCoreApiHealth(fetchCoreApiHealth))
    await waitFor(() => expect(fetchCoreApiHealth).toHaveBeenCalledOnce())

    let refetchPromise!: Promise<void>
    act(() => {
      refetchPromise = result.current.refetch()
    })
    await waitFor(() => expect(fetchCoreApiHealth).toHaveBeenCalledTimes(2))
    expect(signals[0].aborted).toBe(true)

    unmount()
    expect(signals[1].aborted).toBe(true)

    first.resolve({ service: 'stale-core-api', status: 'down' })
    second.resolve({ service: 'govbiz-core-api', status: 'up' })
    await Promise.all([first.promise, second.promise, refetchPromise])
  })

  it('shows a safe error state and recovers when retry succeeds', async () => {
    const fetchCoreApiHealth = vi.fn()
      .mockRejectedValueOnce(new Error('internal network detail'))
      .mockResolvedValueOnce({ service: 'govbiz-core-api', status: 'up' })
    const { result } = renderHook(() => useCoreApiHealth(fetchCoreApiHealth))
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.data).toBeUndefined()

    await act(async () => {
      await result.current.refetch()
    })
    expect(result.current).toMatchObject({
      data: { service: 'govbiz-core-api', status: 'up' },
      isError: false,
      isLoading: false,
    })
  })
})

function deferred<Result>() {
  let resolve!: (result: Result) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<Result>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, reject, resolve }
}
