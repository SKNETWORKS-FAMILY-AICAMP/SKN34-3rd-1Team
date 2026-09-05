import type {
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

const SEARCH_SUPPORT_PROGRAMS_PATH = '/api/v1/support-programs/search'
const SUPPORT_PROGRAM_DETAIL_PATH = '/api/v1/support-programs/detail'

export class SupportProgramApiError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupportProgramApiError'
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
    throw new SupportProgramApiError(
      `Core API returned HTTP ${response.status} for the support program search request.`,
    )
  }

  return supportProgramSearchResponseDtoSchema.parse(await response.json())
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
