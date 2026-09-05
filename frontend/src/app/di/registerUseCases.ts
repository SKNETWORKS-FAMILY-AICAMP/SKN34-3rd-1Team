import { asFunction } from 'awilix/browser'

import { AskSupportProgramEvidenceQuestionUseCase } from '../../domain/usecases/AskSupportProgramEvidenceQuestionUseCase'
import { GetSupportProgramDetailUseCase } from '../../domain/usecases/GetSupportProgramDetailUseCase'
import { GetSupportProgramSearchReadinessUseCase } from '../../domain/usecases/GetSupportProgramSearchReadinessUseCase'
import { PrepareSampleItemUseCase } from '../../domain/usecases/PrepareSampleItemUseCase'
import { SearchSupportProgramsUseCase } from '../../domain/usecases/SearchSupportProgramsUseCase'
import type { AppContainer, AppCradle } from './types'

/** Domain UseCase와 UseCase가 필요로 하는 Repository 연결을 등록합니다. */
export function registerUseCases(container: AppContainer) {
  container.register({
    askSupportProgramEvidenceQuestionUseCase: asFunction(
      createAskSupportProgramEvidenceQuestionUseCase,
    ).singleton(),
    getSupportProgramDetailUseCase: asFunction(
      createGetSupportProgramDetailUseCase,
    ).singleton(),
    getSupportProgramSearchReadinessUseCase: asFunction(
      createGetSupportProgramSearchReadinessUseCase,
    ).singleton(),
    prepareSampleItemUseCase: asFunction(
      createPrepareSampleItemUseCase,
    ).singleton(),
    searchSupportProgramsUseCase: asFunction(
      createSearchSupportProgramsUseCase,
    ).singleton(),
  })
}

function createAskSupportProgramEvidenceQuestionUseCase({
  supportProgramRepository,
}: Pick<AppCradle, 'supportProgramRepository'>): AskSupportProgramEvidenceQuestionUseCase {
  return new AskSupportProgramEvidenceQuestionUseCase(supportProgramRepository)
}

function createGetSupportProgramDetailUseCase({
  supportProgramRepository,
}: Pick<AppCradle, 'supportProgramRepository'>): GetSupportProgramDetailUseCase {
  return new GetSupportProgramDetailUseCase(supportProgramRepository)
}

function createGetSupportProgramSearchReadinessUseCase({
  supportProgramRepository,
}: Pick<AppCradle, 'supportProgramRepository'>): GetSupportProgramSearchReadinessUseCase {
  return new GetSupportProgramSearchReadinessUseCase(supportProgramRepository)
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
