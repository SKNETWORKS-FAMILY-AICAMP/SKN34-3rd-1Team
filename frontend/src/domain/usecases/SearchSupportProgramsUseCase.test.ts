import { describe, expect, it, vi } from 'vitest'

import { supportPrograms } from '../../data/fixtures/supportPrograms'
import { SearchSupportProgramsUseCase } from './SearchSupportProgramsUseCase'

describe('SearchSupportProgramsUseCase', () => {
  it('returns the repository result without client-side reranking', async () => {
    const rankedPrograms = [supportPrograms[3], supportPrograms[0]]
    const search = vi.fn().mockResolvedValue(rankedPrograms)
    const useCase = new SearchSupportProgramsUseCase({ search })

    const result = await useCase.execute('수출을 준비하는 서울 기업')

    expect(result.programs).toEqual(rankedPrograms)
  })

  it('normalizes the query and forwards request cancellation', async () => {
    const search = vi.fn().mockResolvedValue([])
    const controller = new AbortController()
    const cancellableUseCase = new SearchSupportProgramsUseCase({ search })

    await cancellableUseCase.execute('  서울 AI  ', controller.signal)

    expect(search).toHaveBeenCalledWith(
      { acceptingOnly: true, query: '서울 AI' },
      controller.signal,
    )
  })
})
