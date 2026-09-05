import { asFunction } from 'awilix/browser'

import { GetSupportProgramDetailUseCase } from '../../domain/usecases/GetSupportProgramDetailUseCase'
import { PrepareSampleItemUseCase } from '../../domain/usecases/PrepareSampleItemUseCase'
import { SearchSupportProgramsUseCase } from '../../domain/usecases/SearchSupportProgramsUseCase'
import type { AppContainer, AppCradle } from './types'

/** Domain UseCase와 UseCase가 필요로 하는 Repository 연결을 등록합니다. */
export function registerUseCases(container: AppContainer) {
  container.register({
    getSupportProgramDetailUseCase: asFunction(
      createGetSupportProgramDetailUseCase,
    ).singleton(),
    prepareSampleItemUseCase: asFunction(
      createPrepareSampleItemUseCase,
    ).singleton(),
    searchSupportProgramsUseCase: asFunction(
      createSearchSupportProgramsUseCase,
    ).singleton(),
  })
}

function createGetSupportProgramDetailUseCase({
  supportProgramRepository,
}: Pick<AppCradle, 'supportProgramRepository'>): GetSupportProgramDetailUseCase {
  return new GetSupportProgramDetailUseCase(supportProgramRepository)
}

function createPrepareSampleItemUseCase({
  sampleItemRepository,
}: Pick<AppCradle, 'sampleItemRepository'>): PrepareSampleItemUseCase {
  return new PrepareSampleItemUseCase(sampleItemRepository)
}

function createSearchSupportProgramsUseCase({
  supportProgramRepository,
}: Pick<AppCradle, 'supportProgramRepository'>): SearchSupportProgramsUseCase {
  return new SearchSupportProgramsUseCase(supportProgramRepository)
}
