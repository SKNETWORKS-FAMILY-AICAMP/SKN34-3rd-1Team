import { afterEach, describe, expect, it, vi } from 'vitest'

import { supportPrograms } from '../../fixtures/supportPrograms'
import { SupportProgramRepositoryImpl } from '../../repositories/SupportProgramRepositoryImpl'
import {
  supportProgramSearchReadinessDtoSchema,
  toSupportProgramSearchReadiness,
} from '../../models/SupportProgramSearchReadinessDto'
import {
  answerSupportProgramEvidenceQuestionApi,
  getSupportProgramDetailApi,
  getSupportProgramSearchReadinessApi,
  SupportProgramEvidenceApiError,
  SupportProgramApiError,
  searchSupportProgramsApi,
} from '../supportProgramApi'

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
    ['BIZINFO', 'https://www.bizinfo.go.kr/programs/official'],
    ['KSTARTUP', 'https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do'],
    ['KSTARTUP', 'https://k-startup.go.kr/web/contents/bizpbanc-ongoing.do'],
  ])('accepts an allowlisted official URL for %s', async (sourceCode, sourceUrl) => {
    const program = { ...supportPrograms[3], sourceCode, sourceUrl }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '수출',
      programs: [program],
    })))

    await expect(searchSupportProgramsApi({ query: '수출' })).resolves.toEqual({
      query: '수출',
      programs: [program],
    })
  })

  it.each([
    ['BIZINFO', 'javascript:alert(document.cookie)'],
    ['OTHER', 'data:text/html,unsafe'],
    ['BIZINFO', 'https://bizinfo.go.kr.attacker.example/program'],
    ['OTHER', 'https://bizinfo.go.kr/program'],
    ['OTHER', 'https://support-programs.other.test/programs/official'],
    ['UNKNOWN', 'https://www.bizinfo.go.kr/program'],
    ['BIZINFO', 'https://attacker@www.bizinfo.go.kr/program'],
    ['BIZINFO', 'https://www.bizinfo.go.kr:444/program'],
    ['KSTARTUP', 'https://k-startup.go.kr.attacker.example/program'],
    ['KSTARTUP', 'https://www.bizinfo.go.kr/program'],
    ['BIZINFO', 'https://www.k-startup.go.kr/program'],
    ['KSTARTUP', 'https://attacker@www.k-startup.go.kr/program'],
    ['KSTARTUP', 'https://www.k-startup.go.kr:444/program'],
    ['KSTARTUP', 'javascript:alert(document.cookie)'],
  ])('rejects a non-official or unsafe source URL for %s: %s', async (sourceCode, sourceUrl) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '수출',
      programs: [{ ...supportPrograms[3], sourceCode, sourceUrl }],
    })))

    await expect(searchSupportProgramsApi({ query: '수출' })).rejects.toThrow()
  })

  it('turns a non-success response into a safe API boundary error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))

    await expect(searchSupportProgramsApi({ query: '수출' }))
      .rejects.toBeInstanceOf(SupportProgramApiError)
  })

  it('accepts mixed official providers without losing their source identity', async () => {
    const programs = [supportPrograms[0], {
      ...supportPrograms[1],
      id: supportPrograms[0].id,
      sourceCode: 'KSTARTUP',
      sourceName: 'K-Startup',
      sourceUrl: 'https://www.k-startup.go.kr/web/contents/bizpbanc-ongoing.do',
    }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ query: '창업', programs })))

    await expect(new SupportProgramRepositoryImpl().search({ query: '창업' })).resolves.toEqual(programs)
  })

  it.each([
    ['KSTARTUP', 'https://k-startup.go.kr.attacker.example/program'],
    ['UNKNOWN', 'https://www.k-startup.go.kr/program'],
  ])('rejects the whole mixed response if one %s program has an untrusted URL', async (sourceCode, sourceUrl) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      query: '창업',
      programs: [supportPrograms[0], { ...supportPrograms[1], sourceCode, sourceUrl }],
    })))

    await expect(new SupportProgramRepositoryImpl().search({ query: '창업' })).rejects.toThrow()
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

describe('getSupportProgramSearchReadinessApi', () => {
  it('gets and validates the current readiness with the caller cancellation signal', async () => {
    const controller = new AbortController()
    const readiness = {
      searchState: 'SEARCHABLE_WITH_SYNC_FAILURE',
      programCount: 42,
      indexReady: true,
      lastSuccessfulSyncAt: '2026-09-05T09:00:00+09:00',
      lastFailedSyncAt: '2026-09-05T10:00:00+09:00',
      sources: [{
        sourceCode: 'BIZINFO',
        sourceName: '기업마당',
        searchState: 'SEARCHABLE_WITH_SYNC_FAILURE',
        programCount: 42,
        indexReady: true,
        lastSuccessfulSyncAt: '2026-09-05T09:00:00+09:00',
        lastFailedSyncAt: '2026-09-05T10:00:00+09:00',
      }],
    }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(readiness)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getSupportProgramSearchReadinessApi(controller.signal)).resolves.toEqual(readiness)
    const [requestUrl, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(requestUrl).pathname).toBe('/api/v1/support-programs/readiness')
    expect(init.headers).toEqual({ Accept: 'application/json' })
    expect(init.signal).toBe(controller.signal)
    expect(init.cache).toBe('no-store')
    await expect(new SupportProgramRepositoryImpl().getSearchReadiness()).resolves.toEqual(readiness)
  })

  it('accepts partial search readiness and copies each source into the domain model', () => {
    const source = {
      sourceCode: 'BIZINFO', sourceName: '기업마당', searchState: 'SEARCHABLE',
      programCount: 42, indexReady: true,
      lastSuccessfulSyncAt: '2026-09-05T09:00:00+09:00', lastFailedSyncAt: null,
    }
    const dto = supportProgramSearchReadinessDtoSchema.parse({
      ...source,
      searchState: 'SEARCHABLE_WITH_PARTIAL_SOURCES',
      sources: [source, {
        ...source, sourceCode: 'KSTARTUP', sourceName: 'K-Startup', searchState: 'PREPARING',
        programCount: 0, indexReady: false, lastSuccessfulSyncAt: null,
      }],
    })
    const model = toSupportProgramSearchReadiness(dto)

    expect(model).toEqual(dto)
    expect(model.sources).not.toBe(dto.sources)
    expect(model.sources[0]).not.toBe(dto.sources[0])
    expect(supportProgramSearchReadinessDtoSchema.safeParse({ ...dto, sources: undefined }).success).toBe(false)
    expect(supportProgramSearchReadinessDtoSchema.safeParse({
      ...dto, sources: [{ ...source, searchState: 'SEARCHABLE_WITH_PARTIAL_SOURCES' }],
    }).success).toBe(false)
  })

  it('rejects malformed readiness payloads and HTTP failures at the data boundary', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        searchState: 'SEARCHABLE',
        programCount: -1,
        indexReady: true,
        lastSuccessfulSyncAt: null,
        lastFailedSyncAt: null,
        sources: [{
          sourceCode: 'BIZINFO', sourceName: '기업마당', searchState: 'SEARCHABLE',
          programCount: 0, indexReady: true, lastSuccessfulSyncAt: null, lastFailedSyncAt: null,
        }],
      }))
      .mockResolvedValueOnce(new Response('', { status: 503 })))

    await expect(getSupportProgramSearchReadinessApi()).rejects.toThrow()
    await expect(getSupportProgramSearchReadinessApi()).rejects.toBeInstanceOf(SupportProgramApiError)
  })
})

describe('getSupportProgramDetailApi', () => {
  it('uses the complete source identity and maps the detail response', async () => {
    const controller = new AbortController()
    const detail = { ...supportPrograms[0], matchedReasons: [], recommendationScore: null }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(detail)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getSupportProgramDetailApi({
      sourceCode: detail.sourceCode,
      sourceProgramId: detail.id,
    }, controller.signal)).resolves.toEqual(detail)

    const [requestUrl, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const url = new URL(requestUrl)
    expect(url.pathname).toBe('/api/v1/support-programs/detail')
    expect(url.searchParams.get('sourceCode')).toBe(detail.sourceCode)
    expect(url.searchParams.get('sourceProgramId')).toBe(detail.id)
    expect(init.headers).toEqual({ Accept: 'application/json' })
    expect(init.signal).toBe(controller.signal)

    await expect(new SupportProgramRepositoryImpl().getDetail({
      sourceCode: detail.sourceCode,
      sourceProgramId: detail.id,
    })).resolves.toEqual(detail)
  })

  it('returns null when the program is missing or inactive', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 404 })))

    await expect(getSupportProgramDetailApi({
      sourceCode: 'BIZINFO',
      sourceProgramId: 'missing',
    })).resolves.toBeNull()
  })

  it('rejects a successful response whose source identity differs from the request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      ...supportPrograms[0],
      id: 'different-program-id',
    })))

    await expect(getSupportProgramDetailApi({
      sourceCode: supportPrograms[0].sourceCode,
      sourceProgramId: supportPrograms[0].id,
    })).rejects.toBeInstanceOf(SupportProgramApiError)
  })
})

describe('answerSupportProgramEvidenceQuestionApi', () => {
  const command = {
    sourceCode: 'BIZINFO',
    sourceProgramId: supportPrograms[0].id,
    question: '신청 대상은 누구인가요?',
  }

  it('sends an explicit JSON question with the source identity and returns validated citations', async () => {
    const controller = new AbortController()
    const answer = answeredEvidenceResponse()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(answer))
    vi.stubGlobal('fetch', fetchMock)

    await expect(answerSupportProgramEvidenceQuestionApi(command, controller.signal))
      .resolves.toEqual(answer)

    const [requestUrl, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(requestUrl).pathname).toBe('/api/v1/support-programs/detail/answers')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({
      Accept: 'application/json',
      'Content-Type': 'application/json',
    })
    expect(JSON.parse(String(init.body))).toEqual(command)
    expect(init.signal).toBe(controller.signal)
  })

  it('rejects an answered response without a citation or a citation outside the official provider URL', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        ...answeredEvidenceResponse(),
        citations: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        ...answeredEvidenceResponse(),
        citations: [{
          ...answeredEvidenceResponse().citations[0],
          sourceUrl: 'https://attacker.example/evidence',
        }],
      })))

    await expect(answerSupportProgramEvidenceQuestionApi(command)).rejects.toThrow()
    await expect(answerSupportProgramEvidenceQuestionApi(command)).rejects.toThrow()
  })

  it('keeps the full cited chunk when the answer evidence appears after the first 500 characters', async () => {
    const excerpt = `${'가'.repeat(1_450)}\n신청 마감일은 2026년 9월 30일입니다.`
    const answer = {
      ...answeredEvidenceResponse(),
      citations: [{ ...answeredEvidenceResponse().citations[0], excerpt }],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(answer)))

    await expect(answerSupportProgramEvidenceQuestionApi(command)).resolves.toEqual(answer)
  })

  it('AI 응답 길이를 UTF-16 길이가 아닌 유니코드 코드 포인트 기준으로 검증한다', async () => {
    const answerAtMaximumLength = `${'가'.repeat(1_199)}😀`
    const answerOverMaximumLength = `${'가'.repeat(1_200)}😀`
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        ...answeredEvidenceResponse(),
        answer: answerAtMaximumLength,
      }))
      .mockResolvedValueOnce(jsonResponse({
        ...answeredEvidenceResponse(),
        answer: answerOverMaximumLength,
      })))

    await expect(answerSupportProgramEvidenceQuestionApi(command)).resolves.toMatchObject({
      answer: answerAtMaximumLength,
    })
    await expect(answerSupportProgramEvidenceQuestionApi(command)).rejects.toThrow()
  })

  it('keeps endpoint status private while allowing the Repository to distinguish unsupported and unavailable results', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('', { status: 422 })))

    await expect(answerSupportProgramEvidenceQuestionApi(command)).rejects.toMatchObject({
      name: 'SupportProgramEvidenceApiError',
      status: 422,
    } satisfies Partial<SupportProgramEvidenceApiError>)

    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('', { status: 422 })))
    await expect(new SupportProgramRepositoryImpl().answerEvidenceQuestion(command))
      .resolves.toEqual({ outcome: 'not-supported' })

    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('', { status: 503 })))
    await expect(new SupportProgramRepositoryImpl().answerEvidenceQuestion(command))
      .resolves.toEqual({ outcome: 'unavailable' })
  })

  it('propagates evidence question cancellation to the caller', async () => {
    const controller = new AbortController()
    const aborted = new DOMException('aborted', 'AbortError')
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => new Promise<Response>(
      (_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(aborted), { once: true })
      },
    )))

    const request = answerSupportProgramEvidenceQuestionApi(command, controller.signal)
    controller.abort()

    await expect(request).rejects.toBe(aborted)
  })
})

function answeredEvidenceResponse() {
  return {
    answer: '서울 소재 창업 7년 이내 중소기업이 신청 대상입니다.',
    answerStatus: 'ANSWERED' as const,
    citations: [{
      excerpt: '지원 대상은 서울 소재 창업 7년 이내 중소기업입니다.',
      sourceUrl: supportPrograms[0].sourceUrl,
      chunkOrder: 0,
    }],
  }
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
