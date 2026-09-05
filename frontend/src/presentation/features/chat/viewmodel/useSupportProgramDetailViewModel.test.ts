// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { supportPrograms } from '../../../../data/fixtures/supportPrograms'
import type { SupportProgramIdentity } from '../../../../domain/repositories/SupportProgramRepository'
import type { GetSupportProgramDetailUseCase } from '../../../../domain/usecases/GetSupportProgramDetailUseCase'
import { useSupportProgramDetailViewModel } from './useSupportProgramDetailViewModel'

afterEach(cleanup)

describe('useSupportProgramDetailViewModel', () => {
  it('loads the current program with its complete source identity', async () => {
    const execute = vi.fn().mockResolvedValue(supportPrograms[0])
    const identity = getIdentity()
    const detailUseCase = createDetailUseCase(execute)
    const { result } = renderHook(() => useSupportProgramDetailViewModel(
      identity,
      detailUseCase,
    ))

    expect(result.current).toEqual({ status: 'loading', program: null })
    await waitFor(() => expect(result.current).toEqual({
      status: 'ready',
      program: supportPrograms[0],
    }))
    expect(execute).toHaveBeenCalledWith(identity, expect.any(AbortSignal))
  })

  it('maps a missing detail result to a distinct not-found state', async () => {
    const execute = vi.fn().mockResolvedValue(null)
    const detailUseCase = createDetailUseCase(execute)
    const { result } = renderHook(() => useSupportProgramDetailViewModel(
      getIdentity(),
      detailUseCase,
    ))

    await waitFor(() => expect(result.current).toEqual({ status: 'not-found', program: null }))
  })

  it('aborts the in-flight detail request when the page unmounts', async () => {
    const pending = deferredProgram()
    let requestSignal: AbortSignal | undefined
    const execute = vi.fn((_identity: SupportProgramIdentity, signal?: AbortSignal) => {
      requestSignal = signal
      return pending.promise
    })
    const detailUseCase = createDetailUseCase(execute)
    const { unmount } = renderHook(() => useSupportProgramDetailViewModel(
      getIdentity(),
      detailUseCase,
    ))

    await waitFor(() => expect(execute).toHaveBeenCalledOnce())
    unmount()

    expect(requestSignal?.aborted).toBe(true)
    await act(async () => pending.resolve(supportPrograms[0]))
  })
})

function getIdentity(): SupportProgramIdentity {
  return {
    sourceCode: supportPrograms[0].sourceCode,
    sourceProgramId: supportPrograms[0].id,
  }
}

function createDetailUseCase(
  execute: GetSupportProgramDetailUseCase['execute'],
): Pick<GetSupportProgramDetailUseCase, 'execute'> {
  return { execute }
}

function deferredProgram() {
  type Program = Awaited<ReturnType<GetSupportProgramDetailUseCase['execute']>>
  let resolve!: (program: Program) => void
  const promise = new Promise<Program>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}
