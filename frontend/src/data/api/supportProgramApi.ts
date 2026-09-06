import { z } from 'zod'

import type {
  SupportProgramEvidenceQuestion,
  SupportProgramIdentity,
  SupportProgramSearch,
} from '../../domain/repositories/SupportProgramRepository'
import { getCoreApiBaseUrl } from './coreApiConfig'
import {
  supportProgramDtoSchema,
  supportProgramSearchResponseDtoSchema,
  type SupportProgramDto,
  type SupportProgramSearchResponseDto,
} from '../models/SupportProgramDto'
import {
  supportProgramSearchReadinessDtoSchema,
  type SupportProgramSearchReadinessDto,
} from '../models/SupportProgramSearchReadinessDto'
import {
  parseSupportProgramEvidenceAnswerDto,
  type SupportProgramEvidenceAnswerDto,
} from '../models/SupportProgramEvidenceAnswerDto'

const SEARCH_SUPPORT_PROGRAMS_PATH = '/api/v1/support-programs/search'
const SUPPORT_PROGRAM_SEARCH_READINESS_PATH = '/api/v1/support-programs/readiness'
const SUPPORT_PROGRAM_DETAIL_PATH = '/api/v1/support-programs/detail'
const SUPPORT_PROGRAM_EVIDENCE_ANSWER_PATH = '/api/v1/support-programs/detail/answers'

export class SupportProgramApiError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupportProgramApiError'
  }
}

const requestRejectionProblemSchema = z.object({
  type: z.string().min(1),
  title: z.string().min(1),
  status: z.union([z.literal(429), z.literal(503)]),
  detail: z.string(),
  instance: z.string(),
  code: z.enum(['SUPPORT_PROGRAM_RATE_LIMITED', 'SUPPORT_PROGRAM_BUSY']),
  retryAfterSeconds: z.unknown().optional(),
})

/** 검증한 요청 제한 계약만 보관하며 서버의 원문 오류 문구는 상위 계층에 전달하지 않습니다. */
export class SupportProgramRequestApiError extends SupportProgramApiError {
  readonly code: 'SUPPORT_PROGRAM_RATE_LIMITED' | 'SUPPORT_PROGRAM_BUSY'
  readonly retryAfterSeconds: number | null

  constructor(
    code: 'SUPPORT_PROGRAM_RATE_LIMITED' | 'SUPPORT_PROGRAM_BUSY',
    retryAfterSeconds: number | null,
  ) {
    super('Core API could not admit the support program request.')
    this.name = 'SupportProgramRequestApiError'
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** 원문 근거 답변 endpoint의 HTTP 상태를 Repository가 업무 결과로 변환할 수 있게 합니다. */
export class SupportProgramEvidenceApiError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`Core API returned HTTP ${status} for the support program evidence answer request.`)
    this.name = 'SupportProgramEvidenceApiError'
    this.status = status
  }
}

/** Core API 검색 응답을 런타임에 검증하는 Data Layer의 HTTP 경계입니다. */
export async function searchSupportProgramsApi(
  command: SupportProgramSearch,
  signal?: AbortSignal,
): Promise<SupportProgramSearchResponseDto> {
  const searchParams = new URLSearchParams({
    query: command.query,
    acceptingOnly: String(command.acceptingOnly ?? true),
  })
  const response = await fetch(
    `${getCoreApiBaseUrl()}${SEARCH_SUPPORT_PROGRAMS_PATH}?${searchParams.toString()}`,
    {
      headers: { Accept: 'application/json' },
      signal,
    },
  )

  if (!response.ok) {
    const requestRejection = await readRequestRejection(response)
    if (requestRejection) throw requestRejection
    throw new SupportProgramApiError(
      `Core API returned HTTP ${response.status} for the support program search request.`,
    )
  }

  return supportProgramSearchResponseDtoSchema.parse(await response.json())
}

/** 검색 전에 공고 동기화와 검색 인덱스 준비 상태를 확인합니다. */
export async function getSupportProgramSearchReadinessApi(
  signal?: AbortSignal,
): Promise<SupportProgramSearchReadinessDto> {
  const response = await fetch(
    `${getCoreApiBaseUrl()}${SUPPORT_PROGRAM_SEARCH_READINESS_PATH}`,
    {
      headers: { Accept: 'application/json' },
      signal,
      // 초기 동기화 중 폴링하므로 브라우저의 이전 상태 응답을 재사용하지 않습니다.
      cache: 'no-store',
    },
  )

  if (!response.ok) {
    throw new SupportProgramApiError(
      `Core API returned HTTP ${response.status} for the support program search readiness request.`,
    )
  }

  return supportProgramSearchReadinessDtoSchema.parse(await response.json())
}

/** 공개 원본 식별자로 현재 노출 중인 공고의 상세 정보를 조회합니다. */
export async function getSupportProgramDetailApi(
  identity: SupportProgramIdentity,
  signal?: AbortSignal,
): Promise<SupportProgramDto | null> {
  const searchParams = new URLSearchParams({
    sourceCode: identity.sourceCode,
    sourceProgramId: identity.sourceProgramId,
  })
  const response = await fetch(
    `${getCoreApiBaseUrl()}${SUPPORT_PROGRAM_DETAIL_PATH}?${searchParams.toString()}`,
    {
      headers: { Accept: 'application/json' },
      signal,
    },
  )

  if (response.status === 404) {
    return null
  }

  if (!response.ok) {
    throw new SupportProgramApiError(
      `Core API returned HTTP ${response.status} for the support program detail request.`,
    )
  }

  const detail = supportProgramDtoSchema.parse(await response.json())
  if (
    detail.sourceCode !== identity.sourceCode
    || detail.id !== identity.sourceProgramId
  ) {
    throw new SupportProgramApiError(
      'Core API returned a support program with an identity different from the detail request.',
    )
  }

  return detail
}

/** 특정 공고 원문을 근거로 한 사용자의 명시적 질문에 답합니다. */
export async function answerSupportProgramEvidenceQuestionApi(
  command: SupportProgramEvidenceQuestion,
  signal?: AbortSignal,
): Promise<SupportProgramEvidenceAnswerDto> {
  const response = await fetch(
    `${getCoreApiBaseUrl()}${SUPPORT_PROGRAM_EVIDENCE_ANSWER_PATH}`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(command),
      signal,
    },
  )

  if (!response.ok) {
    const requestRejection = await readRequestRejection(response)
    if (requestRejection) throw requestRejection
    throw new SupportProgramEvidenceApiError(response.status)
  }

  return parseSupportProgramEvidenceAnswerDto(
    await response.json(),
    command.sourceCode,
  )
}

async function readRequestRejection(response: Response): Promise<SupportProgramRequestApiError | null> {
  if (response.status !== 429 && response.status !== 503) return null
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'application/problem+json') {
    return null
  }

  const parsed = requestRejectionProblemSchema.safeParse(await response.json().catch(() => null))
  if (!parsed.success || parsed.data.status !== response.status) return null
  const problem = parsed.data
  if ((response.status === 429 && problem.code !== 'SUPPORT_PROGRAM_RATE_LIMITED')
    || (response.status === 503 && problem.code !== 'SUPPORT_PROGRAM_BUSY')) return null

  const retrySeconds = z.number().int().min(1).max(60).safeParse(problem.retryAfterSeconds)
  const retryHeader = response.headers.get('Retry-After')
  const retryAfterSeconds = retrySeconds.success
    && retryHeader !== null
    && /^[1-9]\d?$/.test(retryHeader)
    && Number(retryHeader) === retrySeconds.data
    ? retrySeconds.data
    : null
  return new SupportProgramRequestApiError(problem.code, retryAfterSeconds)
}
