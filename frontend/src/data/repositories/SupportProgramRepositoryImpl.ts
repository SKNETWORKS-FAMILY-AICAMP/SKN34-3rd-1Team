import { searchSupportProgramsApi } from '../api/supportProgramApi'
import { toSupportProgram } from '../models/SupportProgramDto'
import type { SupportProgram } from '../../domain/entities/SupportProgram'
import type {
  SupportProgramRepository,
  SupportProgramSearch,
} from '../../domain/repositories/SupportProgramRepository'

/** Core API DTO를 검증된 Domain 공고로 변환하는 Repository adapter입니다. */
export class SupportProgramRepositoryImpl implements SupportProgramRepository {
  async search(
    command: SupportProgramSearch,
    signal?: AbortSignal,
  ): Promise<SupportProgram[]> {
    const response = await searchSupportProgramsApi(command, signal)
    return response.programs.map(toSupportProgram)
  }
}
