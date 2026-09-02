// @vitest-environment jsdom

import type { SubmitEvent } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { SampleItem } from '../../../../domain/entities/SampleItem'
import type { SampleItemPreparation } from '../../../../domain/entities/SampleItemPreparation'
import type { PrepareSampleItemUseCase } from '../../../../domain/usecases/PrepareSampleItemUseCase'
import { useSampleItemViewModel } from './useSampleItemViewModel'

afterEach(cleanup)

describe('useSampleItemViewModel', () => {
  it('normalizes the item, blocks duplicate submits, and stores the result', async () => {
    const pending = deferred<SampleItemPreparation>()
    const prepareSampleItem = vi.fn(
      (_item: SampleItem, _signal?: AbortSignal) => pending.promise,
    )
    render(<TestHarness useCase={createPrepareSampleItemUseCase(prepareSampleItem)} />)

    fireEvent.change(screen.getByTestId('name-input'), { target: { value: '예제' } })
    fireEvent.change(screen.getByTestId('note-input'), { target: { value: '  설명  ' } })
    await waitFor(() => expect(submitButton().disabled).toBe(false))

    fireEvent.submit(screen.getByTestId('sample-form'))
    fireEvent.submit(screen.getByTestId('sample-form'))
    await waitFor(() => expect(prepareSampleItem).toHaveBeenCalledOnce())
    expect(prepareSampleItem.mock.calls[0][0]).toEqual({
      category: null,
      name: '예제',
      note: '설명',
    })
    expect(prepareSampleItem.mock.calls[0][1]).toBeInstanceOf(AbortSignal)

    await act(async () => {
      pending.resolve(successfulPreparation('예제'))
      await pending.promise
    })
    expect(screen.getByTestId('preparation').textContent).toBe('예제')
    expect(screen.getByTestId('action-message').textContent).toBe('Core API 요청이 성공했습니다.')
    expect(screen.getByTestId('status').textContent).toBe('idle')
  })

  it('aborts and ignores a pending response when the input changes', async () => {
    const pending = deferred<SampleItemPreparation>()
    let requestSignal: AbortSignal | undefined
    const prepareSampleItem = vi.fn((_item: SampleItem, signal?: AbortSignal) => {
      requestSignal = signal
      return pending.promise
    })
    render(<TestHarness useCase={createPrepareSampleItemUseCase(prepareSampleItem)} />)

    fireEvent.change(screen.getByTestId('name-input'), { target: { value: '이전 값' } })
    await waitFor(() => expect(submitButton().disabled).toBe(false))
    fireEvent.submit(screen.getByTestId('sample-form'))
    await waitFor(() => expect(prepareSampleItem).toHaveBeenCalledOnce())

    fireEvent.change(screen.getByTestId('name-input'), { target: { value: '새 값' } })
    expect(requestSignal?.aborted).toBe(true)
    expect(screen.getByTestId('status').textContent).toBe('idle')

    await act(async () => {
      pending.resolve(successfulPreparation('이전 값'))
      await pending.promise
    })
    expect(screen.queryByTestId('preparation')).toBeNull()
    expect(screen.queryByTestId('preparation-error')).toBeNull()
  })

  it('shows a safe error, resets it on input change, and aborts on unmount', async () => {
    const pending = deferred<SampleItemPreparation>()
    const prepareSampleItem = vi.fn()
      .mockRejectedValueOnce(new Error('private server detail'))
      .mockImplementationOnce((_item: SampleItem, _signal?: AbortSignal) => pending.promise)
    const { unmount } = render(
      <TestHarness useCase={createPrepareSampleItemUseCase(prepareSampleItem)} />,
    )

    fireEvent.change(screen.getByTestId('name-input'), { target: { value: '오류 예제' } })
    await waitFor(() => expect(submitButton().disabled).toBe(false))
    fireEvent.submit(screen.getByTestId('sample-form'))
    await waitFor(() => {
      expect(screen.getByTestId('preparation-error').textContent).toContain('Core API에 예제 요청을 전달하지 못했습니다.')
    })
    expect(screen.getByTestId('preparation-error').textContent).not.toContain('private server detail')

    fireEvent.change(screen.getByTestId('name-input'), { target: { value: '재시도' } })
    expect(screen.queryByTestId('preparation-error')).toBeNull()
    await waitFor(() => expect(submitButton().disabled).toBe(false))
    fireEvent.submit(screen.getByTestId('sample-form'))
    await waitFor(() => expect(prepareSampleItem).toHaveBeenCalledTimes(2))
    const latestSignal = prepareSampleItem.mock.calls[1][1] as AbortSignal

    unmount()
    expect(latestSignal.aborted).toBe(true)
    pending.resolve(successfulPreparation('재시도'))
    await pending.promise
  })

  it('retries the same valid input after a failure', async () => {
    const retryPending = deferred<SampleItemPreparation>()
    const prepareSampleItem = vi.fn()
      .mockRejectedValueOnce(new Error('temporary connection failure'))
      .mockImplementationOnce((_item: SampleItem, _signal?: AbortSignal) => retryPending.promise)
    render(<TestHarness useCase={createPrepareSampleItemUseCase(prepareSampleItem)} />)

    fireEvent.change(screen.getByTestId('name-input'), { target: { value: '같은 입력' } })
    await waitFor(() => expect(submitButton().disabled).toBe(false))
    fireEvent.submit(screen.getByTestId('sample-form'))

    await waitFor(() => expect(screen.getByTestId('preparation-error').textContent).toContain('다시 요청'))
    expect(submitButton().disabled).toBe(false)
    expect(submitButton().textContent).toBe('다시 요청')

    fireEvent.submit(screen.getByTestId('sample-form'))

    await waitFor(() => expect(prepareSampleItem).toHaveBeenCalledTimes(2))
    expect(screen.getByTestId('status').textContent).toBe('pending')
    expect(submitButton().textContent).toBe('다시 요청 중…')
    expect(prepareSampleItem.mock.calls[1][0]).toEqual(prepareSampleItem.mock.calls[0][0])
    expect(prepareSampleItem.mock.calls[1][1]).not.toBe(prepareSampleItem.mock.calls[0][1])

    await act(async () => {
      retryPending.resolve(successfulPreparation('같은 입력'))
      await retryPending.promise
    })

    expect(screen.getByTestId('preparation').textContent).toBe('같은 입력')
    expect(screen.getByTestId('action-message').textContent).toBe('Core API 요청이 성공했습니다.')
    expect(screen.queryByTestId('preparation-error')).toBeNull()
    expect(submitButton().textContent).toBe('다시 확인')
  })
})

function TestHarness({
  useCase,
}: {
  useCase: Pick<PrepareSampleItemUseCase, 'execute'>
}) {
  const viewModel = useSampleItemViewModel(useCase)

  function submit(event: SubmitEvent<HTMLFormElement>) {
    void viewModel.prepare(event)
  }

  return (
    <form data-testid="sample-form" onSubmit={submit}>
      <input data-testid="name-input" {...viewModel.registerField('name')} />
      <textarea data-testid="note-input" {...viewModel.registerField('note')} />
      <button type="submit" disabled={!viewModel.isReady}>
        {viewModel.submitButtonLabel === '준비 상태 확인'
          ? '요청'
          : viewModel.submitButtonLabel}
      </button>
      <span data-testid="action-message">{viewModel.actionMessage}</span>
      <span data-testid="status">{viewModel.isPreparing ? 'pending' : 'idle'}</span>
      {viewModel.preparation ? (
        <output data-testid="preparation">{viewModel.preparation.item.name}</output>
      ) : null}
      {viewModel.preparationError ? (
        <p data-testid="preparation-error">{viewModel.preparationError}</p>
      ) : null}
    </form>
  )
}

function submitButton() {
  return screen.getByRole('button') as HTMLButtonElement
}

function createPrepareSampleItemUseCase(
  execute: PrepareSampleItemUseCase['execute'],
): Pick<PrepareSampleItemUseCase, 'execute'> {
  return { execute }
}

function successfulPreparation(name: string): SampleItemPreparation {
  return {
    item: { category: null, name, note: null },
    phase: 'READY_FOR_PROCESSING',
    processing: { status: 'NOT_STARTED' },
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
