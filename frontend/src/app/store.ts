import { configureStore } from '@reduxjs/toolkit'

import chatReducer from '../presentation/features/chat/state/chatSlice'
import sampleItemReducer from '../presentation/features/sample-item/state/sampleItemSlice'

export function createAppStore() {
  return configureStore({
    reducer: {
      chat: chatReducer,
      sampleItem: sampleItemReducer,
    },
  })
}

export type AppStore = ReturnType<typeof createAppStore>
export type RootState = ReturnType<AppStore['getState']>
export type AppDispatch = AppStore['dispatch']
