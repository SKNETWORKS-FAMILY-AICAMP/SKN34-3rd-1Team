import { CombinationReviewListPage, CombinationReviewEditorPage } from './presentation/features/combination-review/view/CombinationReviewPages'
import { useReviewSessionIsolation } from './presentation/features/combination-review/viewmodel/useReviewSessionIsolation'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router'

import { AdminMembersPage } from './presentation/features/admin/view/AdminMembersPage'
import { SavedProgramsPage } from './presentation/features/saved-programs/view/SavedProgramsPage'
import { LoginPage } from './presentation/features/auth/view/LoginPage'
import { SignupPage } from './presentation/features/auth/view/SignupPage'
import { SupportProgramSearchPage } from './presentation/features/support-program-catalog/view/SupportProgramSearchPage'
import { CompanyProfilePage } from './presentation/features/company-profile/view/CompanyProfilePage'
import { PartnerRecruitmentCreatePage } from './presentation/features/partner-recruitment/view/PartnerRecruitmentCreatePage'
import { PartnerRecruitmentDetailPage } from './presentation/features/partner-recruitment/view/PartnerRecruitmentDetailPage'
import { PartnerRecruitmentListPage } from './presentation/features/partner-recruitment/view/PartnerRecruitmentListPage'
import { PartnerProposalBoxPage } from './presentation/features/partner-proposal/view/PartnerProposalBoxPage'
import { PricingPage } from './presentation/features/pricing/view/PricingPage'
import { PublicPartnerRecruitmentDetailPage } from './presentation/features/public-partner-recruitment/view/PublicPartnerRecruitmentDetailPage'
import { PublicPartnerRecruitmentListPage } from './presentation/features/public-partner-recruitment/view/PublicPartnerRecruitmentListPage'
import { SupportProgramDetailPage } from './presentation/features/support-program-detail/view/SupportProgramDetailPage'
import { SupportProgramEvidenceQuestionPage } from './presentation/features/support-program-detail/view/SupportProgramEvidenceQuestionPage'
import { ReduxSampleItemPage } from './presentation/features/sample-item/view/ReduxSampleItemPage'
import { SampleItemPage } from './presentation/features/sample-item/view/SampleItemPage'
import { AppHeader } from './presentation/shared/app-header/AppHeader'
import { WorkspaceLayout } from './presentation/shared/app-sidebar/WorkspaceLayout'
import { useRestoreAuthSession } from './presentation/shared/auth/hooks/useAuthSession'
import { GuestOnly, PublicOnly, RequireAuth } from './presentation/shared/auth/RouteGuards'
import { APP_PREFIX, appPaths, publicPaths } from './presentation/shared/routes/appPaths'

/** 로그인 전 화면들의 레이아웃입니다. 공용 헤더가 브랜드와 로그인 진입점을 담당합니다. */
function PublicLayout() {
  const { pathname } = useLocation()
  return (
    <div className={pathname === '/' ? 'flex h-dvh flex-col overflow-hidden bg-white' : undefined}>
      <AppHeader />
      <Outlet />
    </div>
  )
}

/**
 * 로그인 전에는 `/` 아래 공개 화면을 헤더와 함께, 로그인 뒤에는 `/app` 아래 내부 화면을 사이드바와 함께 씁니다.
 * 로그인한 사용자가 공개 URL로 오면 같은 내용의 내부 화면으로 보내고, 비로그인으로 `/app`에 오면 로그인으로 보냅니다.
 * 로그인·회원가입은 둘 다 쓰지 않는 단독 화면이며 로그인 상태에서는 작업 화면으로 돌려보냅니다.
 */
function App() {
  useRestoreAuthSession()
  useReviewSessionIsolation()

  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route element={<PublicOnly />}>
          <Route path={publicPaths.landing} element={<SupportProgramSearchPage />} />
          <Route path={publicPaths.pricing} element={<PricingPage />} />
          <Route path={publicPaths.partners} element={<PublicPartnerRecruitmentListPage />} />
          <Route path={publicPaths.partnerDetail} element={<PublicPartnerRecruitmentDetailPage />} />
          <Route path={publicPaths.supportProgramDetail} element={<SupportProgramDetailPage />} />
          <Route path={publicPaths.supportProgramQuestion} element={<SupportProgramEvidenceQuestionPage />} />
        </Route>
        {/* 상태관리 비교 예제는 개발용 화면이라 로그인 여부와 무관하게 같은 헤더 아래에서 엽니다. */}
        <Route path="/examples/sample-item/hook" element={<SampleItemPage />} />
        <Route path="/examples/sample-item/redux" element={<ReduxSampleItemPage />} />
      </Route>

      <Route element={<GuestOnly />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
      </Route>

      <Route element={<RequireAuth />}>
        <Route element={<WorkspaceLayout />}>
          <Route path={appPaths.savedPrograms} element={<SavedProgramsPage />} />
          <Route path={appPaths.combinationReviews} element={<CombinationReviewListPage />} />
          <Route path={appPaths.combinationReviewNew} element={<CombinationReviewEditorPage create />} />
          <Route path={appPaths.combinationReviewDetail} element={<CombinationReviewEditorPage />} />
          <Route path={appPaths.chat} element={<SupportProgramSearchPage layout="workspace" />} />
          <Route path={appPaths.pricing} element={<PricingPage layout="workspace" />} />
          <Route path={appPaths.partners} element={<PartnerRecruitmentListPage />} />
          <Route path={appPaths.partnerNew} element={<PartnerRecruitmentCreatePage />} />
          <Route path={appPaths.partnerDetail} element={<PartnerRecruitmentDetailPage />} />
          <Route path={appPaths.proposals} element={<PartnerProposalBoxPage />} />
          <Route path={appPaths.profile} element={<CompanyProfilePage />} />
          <Route path={appPaths.supportProgramDetail} element={<SupportProgramDetailPage />} />
          <Route path={appPaths.supportProgramQuestion} element={<SupportProgramEvidenceQuestionPage />} />
        </Route>
      </Route>

      <Route element={<RequireAuth minimumTier="ADMIN" />}>
        <Route element={<WorkspaceLayout />}>
          <Route path={appPaths.adminMembers} element={<AdminMembersPage />} />
        </Route>
      </Route>

      <Route path={APP_PREFIX} element={<Navigate replace to={appPaths.chat} />} />
      <Route path="*" element={<Navigate replace to={publicPaths.landing} />} />
    </Routes>
  )
}

export default App
