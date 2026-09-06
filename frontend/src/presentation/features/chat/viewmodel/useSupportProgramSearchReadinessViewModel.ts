import { useCallback, useEffect, useRef, useState } from 'react'

import { appContainer } from '../../../../app/appContainer'
import type { SupportProgramSearchReadiness } from '../../../../domain/entities/SupportProgramSearchReadiness'
import type { GetSupportProgramSearchReadinessUseCase } from '../../../../domain/usecases/GetSupportProgramSearchReadinessUseCase'

/** 제공처의 초기 동기화 중 새로고침 없이 준비 완료를 반영하는 간격입니다. */
export const supportProgramReadinessPollingMilliseconds = 5_000

type SupportProgramSearchReadinessUseCase = Pick<
  GetSupportProgramSearchReadinessUseCase,
  'execute'
>

type SupportProgramSearchReadinessState = {
  data: SupportProgramSearchReadiness | undefined
  isError: boolean
  isInitialLoading: boolean
  isRefreshing: boolean
}

const initialState: SupportProgramSearchReadinessState = {
  data: undefined,
  isError: false,
  isInitialLoading: true,
  isRefreshing: false,
}

/** 검색 화면에서 현재 공고 데이터가 검색 가능한지 확인하고 초기 준비 중에는 다시 조회합니다. */
export function useSupportProgramSearchReadinessViewModel(
  getSupportProgramSearchReadinessUseCase: SupportProgramSearchReadinessUseCase = appContainer.resolve(
    'getSupportProgramSearchReadinessUseCase',
  ),
) {
  const activeController = useRef<AbortController | null>(null)
  const activeRequestId = useRef(0)
  const isMounted = useRef(false)
  const [state, setState] = useState<SupportProgramSearchReadinessState>(initialState)

  const refetch = useCallback(async () => {
    activeController.current?.abort()

    const controller = new AbortController()
    const requestId = activeRequestId.current + 1
    activeController.current = controller
    activeRequestId.current = requestId
    setState((currentState) => ({
      ...currentState,
      isError: false,
      isInitialLoading: currentState.data === undefined,
      isRefreshing: currentState.data !== undefined,
    }))

    try {
      const data = await getSupportProgramSearchReadinessUseCase.execute(controller.signal)
      if (!isMounted.current || activeRequestId.current !== requestId) return
      setState({
        data,
        isError: false,
        isInitialLoading: false,
        isRefreshing: false,
      })
    } catch {
      if (
        !isMounted.current
        || activeRequestId.current !== requestId
        || controller.signal.aborted
      ) return
      setState((currentState) => ({
        ...currentState,
        isError: true,
        isInitialLoading: false,
        isRefreshing: false,
      }))
    }
  }, [getSupportProgramSearchReadinessUseCase])

  useEffect(() => {
    isMounted.current = true
    void refetch()

    return () => {
      isMounted.current = false
      activeRequestId.current += 1
      activeController.current?.abort()
    }
  }, [refetch])

  useEffect(() => {
    const isPreparing = state.data?.searchState === 'PREPARING'
      || state.data?.sources.some((source) => source.searchState === 'PREPARING')
    if (!isPreparing || state.isRefreshing) return

    const pollingTimeout = setTimeout(() => {
      void refetch()
    }, supportProgramReadinessPollingMilliseconds)
    return () => clearTimeout(pollingTimeout)
  }, [refetch, state.data, state.isRefreshing])

  const canSearch = !state.isError && (
    state.data?.searchState === 'SEARCHABLE'
    || state.data?.searchState === 'SEARCHABLE_WITH_SYNC_FAILURE'
    || state.data?.searchState === 'SEARCHABLE_WITH_PARTIAL_SOURCES'
  )

  return {
    ...state,
    canSearch,
    refetch,
  }
}
