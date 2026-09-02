import { useCallback, useEffect, useRef, useState } from 'react'

import { appContainer } from '../../../app/appContainer'
import type { FetchCoreApiHealth } from '../../../app/di/types'
import type { CoreApiHealth } from '../../../data/api/coreApiHealth'

type CoreApiHealthState = {
  data: CoreApiHealth | undefined
  isError: boolean
  isLoading: boolean
}

const initialState: CoreApiHealthState = {
  data: undefined,
  isError: false,
  isLoading: true,
}

/** Core API Health 요청과 화면 수명에 따른 취소를 직접 관리합니다. */
export function useCoreApiHealth(
  fetchCoreApiHealth: FetchCoreApiHealth = appContainer.resolve(
    'fetchCoreApiHealth',
  ),
) {
  const activeController = useRef<AbortController | null>(null)
  const activeRequestId = useRef(0)
  const isMounted = useRef(false)
  const [state, setState] = useState<CoreApiHealthState>(initialState)

  const refetch = useCallback(async () => {
    activeController.current?.abort()

    const controller = new AbortController()
    const requestId = activeRequestId.current + 1
    activeController.current = controller
    activeRequestId.current = requestId
    setState({ data: undefined, isError: false, isLoading: true })

    try {
      const data = await fetchCoreApiHealth(controller.signal)
      if (!isMounted.current || activeRequestId.current !== requestId) return
      setState({ data, isError: false, isLoading: false })
    } catch {
      if (
        !isMounted.current
        || activeRequestId.current !== requestId
        || controller.signal.aborted
      ) return
      setState({ data: undefined, isError: true, isLoading: false })
    }
  }, [fetchCoreApiHealth])

  useEffect(() => {
    isMounted.current = true
    void refetch()

    return () => {
      isMounted.current = false
      activeRequestId.current += 1
      activeController.current?.abort()
    }
  }, [refetch])

  return { ...state, refetch }
}
