import { afterEach, describe, expect, it, vi } from 'vitest'

import { supportPrograms } from '../../fixtures/supportPrograms'
import { SupportProgramRepositoryImpl } from '../../repositories/SupportProgramRepositoryImpl'
import { SupportProgramApiError, searchSupportProgramsApi } from '../supportProgramApi'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('searchSupportProgramsApi', () => {
  it('encodes the search command and supplies the abort signal', async () => {
    const controller = new AbortController()
    const responseBody = { query: '서울 AI', programs: [supportPrograms[0]] }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(responseBody))
    vi.stubGlobal('fetch', fetchMock)

    await expect(searchSupportProgramsApi(
      { query: '서울 AI', acceptingOnly: false },
      controller.signal,
    )).resolves.toEqual(responseBody)

    const [requestUrl, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const url = new URL(requestUrl)
    expect(url.pathname).toBe('/api/v1/support-programs/search')
    expect(url.searchParams.get('query')).toBe('서울 AI')
    expect(url.searchParams.get('acceptingOnly')).toBe('false')
    expect(init.headers).toEqual({ Accept: 'application/json' })
    expect(init.signal).toBe(controller.signal)
  })

  it('accepts a non-date application period and maps it into a domain program', async () => {
    const dto = {
      ...supportPrograms[4],
      applicationPeriod: '예산 소진시까지',
      applicationStartDate: null,
      applicationEndDate: null,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '콘텐츠',
      programs: [dto],
    })))

    const programs = await new SupportProgramRepositoryImpl().search({ query: '콘텐츠' })

    expect(programs[0]).toEqual(dto)
    expect(programs[0]).not.toBe(dto)
    expect(programs[0]?.categories).not.toBe(dto.categories)
  })

  it('rejects a response that violates the runtime contract', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '수출',
      programs: [{ ...supportPrograms[3], status: 'INVALID' }],
    })))

    await expect(searchSupportProgramsApi({ query: '수출' })).rejects.toThrow()
  })

  it('rejects a blank application period that would render an empty deadline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '수출',
      programs: [{ ...supportPrograms[3], applicationPeriod: '' }],
    })))

    await expect(searchSupportProgramsApi({ query: '수출' })).rejects.toThrow()
  })

  it.each([
    'javascript:alert(document.cookie)',
    'https://bizinfo.go.kr.attacker.example/program',
    'https://example.com/program',
  ])('rejects a non-official source URL: %s', async (sourceUrl) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '수출',
      programs: [{ ...supportPrograms[3], sourceUrl }],
    })))

    await expect(searchSupportProgramsApi({ query: '수출' })).rejects.toThrow()
  })

  it('turns a non-success response into a safe API boundary error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))

    await expect(searchSupportProgramsApi({ query: '수출' }))
      .rejects.toBeInstanceOf(SupportProgramApiError)
  })

  it('propagates request cancellation to the caller', async () => {
    const controller = new AbortController()
    const aborted = new DOMException('aborted', 'AbortError')
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => new Promise<Response>(
      (_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(aborted), { once: true })
      },
    )))

    const request = searchSupportProgramsApi({ query: '서울' }, controller.signal)
    controller.abort()

    await expect(request).rejects.toBe(aborted)
  })
})

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
