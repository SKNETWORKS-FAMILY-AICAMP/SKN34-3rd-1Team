// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from './App'
import { createAppStore } from './app/store'
import { supportPrograms } from './data/fixtures/supportPrograms'

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

  it('검색 결과의 상세 조건 보기는 URL 기반 API 조회 화면으로 연결한다', async () => {
    const detail = { ...supportPrograms[0], matchedReasons: [], recommendationScore: null }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        query: '서울 AI',
        programs: [supportPrograms[0]],
      }))
      .mockResolvedValueOnce(jsonResponse(detail))
    vi.stubGlobal('fetch', fetchMock)

    renderApp(createAppStore())

    const chatInput = screen.getByPlaceholderText('예: 서울에서 AI 창업지원 사업을 찾아줘')
    fireEvent.change(chatInput, { target: { value: '서울 AI' } })
    fireEvent.submit(chatInput.closest('form')!)

    const detailLink = await screen.findByRole('link', { name: '상세 조건 보기' })
    fireEvent.click(detailLink)

    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('서울 소재 창업 7년 이내 중소기업')).toBeTruthy()
    expect(screen.getByText('접수 중')).toBeTruthy()
    expect(screen.queryByText('이 공고를 추천한 이유')).toBeNull()

    const detailRequestUrl = new URL(String(fetchMock.mock.calls[1]?.[0]))
    expect(detailRequestUrl.pathname).toBe('/api/v1/support-programs/detail')
    expect(detailRequestUrl.searchParams.get('sourceCode')).toBe(supportPrograms[0].sourceCode)
    expect(detailRequestUrl.searchParams.get('sourceProgramId')).toBe(supportPrograms[0].id)

    const sourceLink = screen.getByRole('link', { name: /GovBiz 샘플 데이터 원문 보기/ })
    expect(sourceLink.getAttribute('href')).toBe(supportPrograms[0].sourceUrl)
    expect(sourceLink.getAttribute('target')).toBe('_blank')
    expect(sourceLink.getAttribute('rel')).toBe('noreferrer')

    fireEvent.click(screen.getByRole('link', { name: '← 검색 결과로 돌아가기' }))
    expect(screen.getByRole('heading', { name: 'GovBiz에게 물어보세요' })).toBeTruthy()
  })

  it('새로고침 또는 공유 URL의 직접 진입도 Core API에서 상세 정보를 다시 조회한다', async () => {
    const detail = { ...supportPrograms[0], matchedReasons: [], recommendationScore: null }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(detail))
    vi.stubGlobal('fetch', fetchMock)

    renderApp(
      createAppStore(),
      `/support-programs/detail?sourceCode=${encodeURIComponent(detail.sourceCode)}&sourceProgramId=${encodeURIComponent(detail.id)}`,
    )

    expect(screen.getByRole('heading', { name: '공고 정보를 불러오는 중입니다' })).toBeTruthy()
    await screen.findByRole('heading', { name: detail.title })
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('존재하지 않거나 비활성화된 공고는 404 안내를 보여 준다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 404 })))

    renderApp(createAppStore(), '/support-programs/detail?sourceCode=BIZINFO&sourceProgramId=unknown-program')

    await screen.findByRole('heading', { name: '공고 정보를 찾을 수 없습니다' })
    expect(screen.getByText(/존재하지 않거나 더 이상 제공되지 않는 공고입니다/)).toBeTruthy()
    expect(screen.getByRole('link', { name: '← 검색 결과로 돌아가기' })).toBeTruthy()
  })

  it.each([
    '/support-programs/detail',
    '/support-programs/detail?sourceCode=BIZINFO',
    '/support-programs/detail?sourceProgramId=missing-source-code',
    '/support-programs/detail?sourceCode=%20&sourceProgramId=blank-source-code',
    '/support-programs/detail?sourceCode=BIZINFO&sourceProgramId=%20',
  ])('식별자가 누락되거나 공백인 URL(%s)은 API를 호출하지 않는다', (path) => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderApp(createAppStore(), path)

    expect(screen.getByRole('heading', { name: '공고 정보를 찾을 수 없습니다' })).toBeTruthy()
    expect(screen.getByText('공고 주소가 올바르지 않습니다. 검색 결과에서 공고를 다시 선택해 주세요.'))
      .toBeTruthy()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('상세 조회 API가 실패하면 안전한 오류 안내를 보여 준다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))

    renderApp(
      createAppStore(),
      '/support-programs/detail?sourceCode=BIZINFO&sourceProgramId=temporarily-unavailable',
    )

    await screen.findByRole('heading', { name: '공고 정보를 불러오지 못했습니다' })
    expect(screen.getByText(/잠시 후 다시 시도해 주세요/)).toBeTruthy()
  })

  it('퍼센트와 슬래시가 포함된 원본 공고 ID도 URL 인코딩 후 상세 조회한다', async () => {
    const program = {
      ...supportPrograms[0],
      id: 'fixture%20/program?',
      matchedReasons: [],
      recommendationScore: null,
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(program))
    vi.stubGlobal('fetch', fetchMock)

    renderApp(
      createAppStore(),
      `/support-programs/detail?sourceCode=${encodeURIComponent(program.sourceCode)}&sourceProgramId=${encodeURIComponent(program.id)}`,
    )

    await screen.findByRole('heading', { name: program.title })
    const detailRequestUrl = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(detailRequestUrl.searchParams.get('sourceProgramId')).toBe(program.id)
  })
})

function renderApp(
  appStore: ReturnType<typeof createAppStore>,
  initialEntry = '/',
) {
  return render(
    <Provider store={appStore}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <App />
      </MemoryRouter>
    </Provider>,
  )
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
