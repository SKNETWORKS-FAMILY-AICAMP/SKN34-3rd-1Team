import type { SupportProgram } from '../entities/SupportProgram'
import type { SupportProgramRepository } from '../repositories/SupportProgramRepository'

export type SearchSupportProgramsResult = {
  programs: SupportProgram[]
  query: string
}

export class SearchSupportProgramsUseCase {
  private readonly repository: SupportProgramRepository

  constructor(repository: SupportProgramRepository) {
    this.repository = repository
  }

  async execute(query: string, signal?: AbortSignal): Promise<SearchSupportProgramsResult> {
    const normalizedQuery = query.trim()
    return {
      query: normalizedQuery,
      programs: await this.repository.search(
        { query: normalizedQuery, acceptingOnly: true },
        signal,
      ),
    }
  }
}
