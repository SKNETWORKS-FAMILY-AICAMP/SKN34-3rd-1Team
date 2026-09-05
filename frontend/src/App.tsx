import { Navigate, Route, Routes } from 'react-router'

import { ChatPage } from './presentation/features/chat/view/ChatPage'
import { SupportProgramDetailPage } from './presentation/features/chat/view/SupportProgramDetailPage'
import { ReduxSampleItemPage } from './presentation/features/sample-item/view/ReduxSampleItemPage'
import { SampleItemPage } from './presentation/features/sample-item/view/SampleItemPage'

/** GovBiz의 첫 진입점은 공고를 찾는 채팅 화면입니다. */
function App() {
  return (
    <Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/support-programs/:programId" element={<SupportProgramDetailPage />} />
      <Route path="/examples/sample-item/hook" element={<SampleItemPage />} />
      <Route path="/examples/sample-item/redux" element={<ReduxSampleItemPage />} />
      <Route path="*" element={<Navigate replace to="/" />} />
    </Routes>
  )
}

export default App
