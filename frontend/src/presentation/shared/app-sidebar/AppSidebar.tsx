import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'

import type { Account } from '../../../domain/entities/Account'
import { useAuthSession } from '../auth/hooks/useAuthSession'
import { usePendingReceivedProposalCount } from '../partner-proposal/useReceivedProposals'
import { appPaths } from '../routes/appPaths'
import { appSidebarStyles, sidebarMenuItemClassName } from './AppSidebar.styles'

type MenuIcon = 'search' | 'bookmark' | 'users' | 'inbox' | 'building' | 'shield' | 'pricing'

/** 사이드바 메뉴 한 줄입니다. `to`가 없으면 아직 화면이 없는 메뉴이므로 링크로 만들지 않습니다. */
type MenuItem = {
  label: string
  icon: MenuIcon
  to?: string
  badge?: string
  matches?: (pathname: string) => boolean
}

type MenuGroup = { title: string; items: MenuItem[]; adminOnly?: boolean }

const menuGroups: MenuGroup[] = [
  {
    title: '메뉴',
    items: [
      {
        label: '지원사업 검색',
        icon: 'search',
        to: appPaths.chat,
        matches: (pathname) => pathname === appPaths.chat || pathname.startsWith(appPaths.supportProgramDetail),
      },
      { label: '중복 지원·수혜 검토', icon: 'shield', to: appPaths.combinationReviews, matches: (pathname) => pathname.startsWith(appPaths.combinationReviews) },
      { label: '관심 공고함', icon: 'bookmark', to: appPaths.savedPrograms, badge: '시안', matches: (pathname) => pathname === appPaths.savedPrograms },
      {
        label: '파트너 모집',
        icon: 'users',
        to: appPaths.partners,
        matches: (pathname) => pathname.startsWith(appPaths.partners),
      },
      {
        label: '제안함',
        icon: 'inbox',
        to: appPaths.proposals,
        matches: (pathname) => pathname.startsWith(appPaths.proposals),
      },
      {
        label: '내 프로필',
        icon: 'building',
        to: appPaths.profile,
        matches: (pathname) => pathname.startsWith(appPaths.profile),
      },
      { label: '요금제', icon: 'pricing', to: appPaths.pricing, matches: (pathname) => pathname === appPaths.pricing },
    ],
  },
  {
    title: '관리자',
    adminOnly: true,
    items: [
      {
        label: '회원·기업',
        icon: 'shield',
        to: appPaths.adminMembers,
        matches: (pathname) => pathname.startsWith(appPaths.admin),
      },
    ],
  },
]

const iconPaths: Record<MenuIcon, ReactNode> = {
  pricing: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="3" />
      <path d="M3 10h18M7 15h3" />
    </>
  ),
  inbox: (
    <>
      <path d="M4 5h16v14H4z" />
      <path d="M4 13h5l1.5 2h3L15 13h5" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="8" />
      <path d="M21 21l-4.35-4.35" />
    </>
  ),
  bookmark: <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" />,
  users: (
    <>
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </>
  ),
  building: (
    <>
      <rect x="4" y="3" width="16" height="18" rx="2" />
      <path d="M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2" />
    </>
  ),
  shield: <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />,
}

function MenuIconGraphic({ name }: { name: MenuIcon }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {iconPaths[name]}
    </svg>
  )
}

/** 계정 카드에 보여 줄 단계 문구입니다. 기업을 등록하면 상호를, 아니면 등록 안내를 봅니다. */
function tierLabel(account: Account): string {
  if (account.tier === 'ADMIN') return '관리자'
  if (account.company !== null) return `${account.company.companyName} · 기업 회원`
  return account.emailVerified ? '회원 · 기업 미등록' : '회원 · 이메일 미인증'
}

/**
 * 로그인 뒤 작업 화면의 사이드바입니다. 공용 헤더를 대신해 화면 이동과 계정 진입점을 담당합니다.
 * 계정 정보는 세션에서 읽고, 관리자 메뉴는 관리자에게만 그리며, 화면이 없는 메뉴는 링크로 만들지 않습니다.
 * 로그인한 사용자는 `/app` 아래에만 머무르므로 공개 화면으로 가는 링크는 두지 않습니다.
 */
export function AppSidebar() {
  const { pathname } = useLocation()
  const { account, logOut } = useAuthSession()
  const pendingProposalCount = usePendingReceivedProposalCount()

  /** 제안함은 받은 제안 대기 건수를 배지로 보여 줍니다. 나머지 메뉴는 고정 문구를 씁니다. */
  function badgeFor(item: MenuItem): string | undefined {
    if (item.to === appPaths.proposals) return pendingProposalCount === null ? undefined : String(pendingProposalCount)
    return item.badge
  }

  return (
    <aside className={appSidebarStyles.sidebar} aria-label="작업 사이드바">
      <Link className={appSidebarStyles.brand} to={appPaths.chat}>
        <span className={appSidebarStyles.brandMark} aria-hidden="true">G</span>
        <span>
          <strong className={appSidebarStyles.brandTitle}>GovBiz</strong>
          <span className={appSidebarStyles.brandSubtitle}>지원사업 탐색 도우미</span>
        </span>
      </Link>

      {menuGroups
        .filter((group) => !group.adminOnly || account?.tier === 'ADMIN')
        .map((group) => (
          <nav className={appSidebarStyles.menuGroup} key={group.title} aria-label={group.title}>
            <p className={appSidebarStyles.menuGroupTitle}>{group.title}</p>
            {group.items.map((item) =>
              item.to ? (
                <Link
                  className={sidebarMenuItemClassName(
                    item.matches?.(pathname) ? 'active' : 'inactive',
                  )}
                  key={item.label}
                  to={item.to}
                  aria-current={item.matches?.(pathname) ? 'page' : undefined}
                >
                  <MenuIconGraphic name={item.icon} />
                  <span>{item.label}</span>
                  {badgeFor(item) ? <span className={appSidebarStyles.menuBadge}>{badgeFor(item)}</span> : null}
                </Link>
              ) : (
                <span
                  className={sidebarMenuItemClassName('pending')}
                  key={item.label}
                  aria-disabled="true"
                >
                  <MenuIconGraphic name={item.icon} />
                  <span>{item.label}</span>
                  {item.badge ? <span className={appSidebarStyles.pendingBadge}>{item.badge}</span> : null}
                </span>
              ),
            )}
          </nav>
        ))}

      {account ? (
        <div className={appSidebarStyles.account}>
          <div className={appSidebarStyles.accountCard}>
            <span className={appSidebarStyles.accountAvatar} aria-hidden="true">
              {account.email.slice(0, 1).toUpperCase()}
            </span>
            <span className="min-w-0">
              <strong className={appSidebarStyles.accountName} title={account.email}>{account.email}</strong>
              <span className={appSidebarStyles.accountCompany}>{tierLabel(account)}</span>
            </span>
          </div>
          <div className={appSidebarStyles.accountActions}>
            <button className={appSidebarStyles.logoutButton} type="button" onClick={() => void logOut()}>
              로그아웃
            </button>
          </div>
        </div>
      ) : null}
    </aside>
  )
}
