import type { SupportProgramSearch } from '../../domain/repositories/SupportProgramRepository'
import { getCoreApiBaseUrl } from './coreApiConfig'
import {
  supportProgramSearchResponseDtoSchema,
  type SupportProgramSearchResponseDto,
} from '../models/SupportProgramDto'

const SEARCH_SUPPORT_PROGRAMS_PATH = '/api/v1/support-programs/search'

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
