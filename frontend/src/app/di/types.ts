import type { AwilixContainer } from 'awilix/browser'

import type { CoreApiHealth } from '../../data/api/coreApiHealth'
import type { SampleItemRepository } from '../../domain/repositories/SampleItemRepository'
import type { SupportProgramRepository } from '../../domain/repositories/SupportProgramRepository'
import type { PrepareSampleItemUseCase } from '../../domain/usecases/PrepareSampleItemUseCase'
import type { SearchSupportProgramsUseCase } from '../../domain/usecases/SearchSupportProgramsUseCase'

export type FetchCoreApiHealth = (signal?: AbortSignal) => Promise<CoreApiHealth>

/** Awilix가 생성·연결할 수 있는 전체 의존성 목록입니다. */
export type AppCradle = {
  fetchCoreApiHealth: FetchCoreApiHealth
  prepareSampleItemUseCase: PrepareSampleItemUseCase
  sampleItemRepository: SampleItemRepository
  searchSupportProgramsUseCase: SearchSupportProgramsUseCase
  supportProgramRepository: SupportProgramRepository
}

export type AppContainer = AwilixContainer<AppCradle>
