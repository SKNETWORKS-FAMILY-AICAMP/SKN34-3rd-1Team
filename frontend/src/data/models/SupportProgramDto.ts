import { z } from 'zod'

import type { SupportProgram } from '../../domain/entities/SupportProgram'

const isoLocalDateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/)
const officialBizInfoUrlSchema = z.string().url().refine((value) => {
  try {
    const url = new URL(value)
    const hostname = url.hostname.toLowerCase()
    return (url.protocol === 'https:' || url.protocol === 'http:')
      && (hostname === 'bizinfo.go.kr' || hostname.endsWith('.bizinfo.go.kr'))
  } catch {
    return false
  }
}, '기업마당 공식 http(s) URL이어야 합니다.')

export const supportProgramDtoSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  organization: z.string(),
  summary: z.string(),
  categories: z.array(z.string()),
  regions: z.array(z.string()),
  targetDescription: z.string(),
  applicationPeriod: z.string().min(1),
  applicationStartDate: isoLocalDateSchema.nullable(),
  applicationEndDate: isoLocalDateSchema.nullable(),
  status: z.enum(['OPEN', 'UPCOMING', 'CLOSED', 'UNKNOWN']),
  sourceName: z.string().min(1),
  sourceUrl: officialBizInfoUrlSchema,
  matchedReasons: z.array(z.string()),
  recommendationScore: z.number().int().min(0).max(100).nullable(),
})

export const supportProgramSearchResponseDtoSchema = z.object({
  query: z.string(),
  programs: z.array(supportProgramDtoSchema),
})

export type SupportProgramDto = z.infer<typeof supportProgramDtoSchema>
export type SupportProgramSearchResponseDto = z.infer<
  typeof supportProgramSearchResponseDtoSchema
>

/** HTTP DTO와 Domain 객체가 우연히 같은 모양이어도 경계를 명시적으로 유지합니다. */
export function toSupportProgram(dto: SupportProgramDto): SupportProgram {
  return {
    id: dto.id,
    title: dto.title,
    organization: dto.organization,
    summary: dto.summary,
    categories: [...dto.categories],
    regions: [...dto.regions],
    targetDescription: dto.targetDescription,
    applicationPeriod: dto.applicationPeriod,
    applicationStartDate: dto.applicationStartDate,
    applicationEndDate: dto.applicationEndDate,
    status: dto.status,
    sourceName: dto.sourceName,
    sourceUrl: dto.sourceUrl,
    matchedReasons: [...dto.matchedReasons],
    recommendationScore: dto.recommendationScore,
  }
}
