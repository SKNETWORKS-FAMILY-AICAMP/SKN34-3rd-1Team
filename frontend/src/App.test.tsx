// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from './App'
import { createAppStore } from './app/store'

vi.mock('./presentation/shared/core-api-status/CoreApiConnectionStatus', () => ({
  CoreApiConnectionStatus: () => null,
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('App navigation', () => {
  it('두 예제의 상태 수명과 Redux의 production DI·HTTP 흐름을 비교한다', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const request = JSON.parse(String(init?.body)) as {
        item: { category: string | null; name: string; note: string | null }
      }

      return new Response(JSON.stringify({
        item: request.item,
        phase: 'READY_FOR_PROCESSING',
        processing: { status: 'NOT_STARTED' },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const appStore = createAppStore()

    expect(Object.keys(appStore.getState())).toEqual(['chat', 'sampleItem'])

    renderApp(appStore)

    expect(screen.getByRole('heading', { name: 'GovBiz에게 물어보세요' })).toBeTruthy()
    const chatInput = screen.getByPlaceholderText('예: 서울에서 AI 창업지원 사업을 찾아줘')
    fireEvent.change(chatInput, { target: { value: '서울 AI 지원사업' } })

    fireEvent.click(screen.getByRole('link', { name: /상태관리 비교 예제/ }))

    expect(screen.getByRole('heading', { name: '재사용 가능한 수직 슬라이스' })).toBeTruthy()

    fireEvent.change(screen.getByRole('textbox', { name: '이름' }), {
      target: { value: 'Hook에서만 유지되는 입력' },
    })

    fireEvent.click(screen.getByRole('link', { name: 'Redux Toolkit 버전' }))
    expect(screen.getByRole('heading', { name: 'Redux 기반 수직 슬라이스' })).toBeTruthy()
    fireEvent.change(screen.getByRole('textbox', { name: '이름' }), {
      target: { value: 'Redux에 유지되는 입력' },
    })

    fireEvent.click(screen.getByRole('link', { name: 'React Hook 버전' }))
    expect(screen.getByRole('heading', { name: '재사용 가능한 수직 슬라이스' })).toBeTruthy()
    expect((screen.getByRole('textbox', { name: '이름' }) as HTMLInputElement).value).toBe('')

    fireEvent.click(screen.getByRole('link', { name: 'Redux Toolkit 버전' }))
    expect((screen.getByRole('textbox', { name: '이름' }) as HTMLInputElement).value).toBe(
      'Redux에 유지되는 입력',
    )

    await waitFor(() => {
      expect((screen.getByRole('button', { name: '준비 상태 확인' }) as HTMLButtonElement).disabled)
        .toBe(false)
    })
    fireEvent.click(screen.getByRole('button', { name: '준비 상태 확인' }))

    await screen.findByText('✓ Redux Store에 요청 성공 저장')
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(String(fetchMock.mock.calls[0]?.[0])).toMatch(/\/api\/v1\/sample-items\/prepare$/)
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      item: {
        category: null,
        name: 'Redux에 유지되는 입력',
        note: null,
      },
    })

    fireEvent.click(screen.getByRole('link', { name: 'React Hook 버전' }))
    fireEvent.click(screen.getByRole('link', { name: 'Redux Toolkit 버전' }))
    expect(screen.getByText('✓ Redux Store에 요청 성공 저장')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Redux 상태 초기화' }))
    expect((screen.getByRole('textbox', { name: '이름' }) as HTMLInputElement).value).toBe('')
    expect(screen.queryByText('✓ Redux Store에 요청 성공 저장')).toBeNull()
    expect((screen.getByRole('button', { name: '준비 상태 확인' }) as HTMLButtonElement).disabled)
      .toBe(true)

    fireEvent.click(screen.getByRole('link', { name: /지원사업 채팅으로 돌아가기/ }))

    expect(screen.getByRole('heading', { name: 'GovBiz에게 물어보세요' })).toBeTruthy()
    expect(
      (screen.getByPlaceholderText('예: 서울에서 AI 창업지원 사업을 찾아줘') as HTMLTextAreaElement)
        .value,
    ).toBe('서울 AI 지원사업')
  })

  it.each([
    ['/examples/sample-item/hook', '재사용 가능한 수직 슬라이스'],
    ['/examples/sample-item/redux', 'Redux 기반 수직 슬라이스'],
  ])('%s URL로 직접 진입한다', (path, heading) => {
    renderApp(createAppStore(), path)

    expect(screen.getByRole('heading', { name: heading })).toBeTruthy()
  })
})

function renderApp(appStore: ReturnType<typeof createAppStore>, initialEntry = '/') {
  return render(
    <Provider store={appStore}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <App />
      </MemoryRouter>
    </Provider>,
  )
}
