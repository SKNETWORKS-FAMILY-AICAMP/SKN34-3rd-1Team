import { asValue } from 'awilix/browser'

import { fetchCoreApiHealth } from '../../data/api/fetchCoreApiHealth'
import type { AppContainer } from './types'

/** UseCase가 아닌 외부 API 기능을 애플리케이션 컨테이너에 등록합니다. */
export function registerExternalServices(container: AppContainer) {
  container.register({
    fetchCoreApiHealth: asValue(fetchCoreApiHealth),
  })
}
