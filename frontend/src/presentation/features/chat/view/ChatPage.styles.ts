function classes(...groups: string[]) {
  return groups.join(' ')
}

// 색상이나 CSS 속성이 아니라 ChatPage에서 맡는 UI 역할을 이름으로 사용합니다.
// open/closed, user/assistant처럼 화면 상태가 달라지는 경우에는 base 스타일과 variant를 분리합니다.
export const chatPageStyles = {
  page: 'grid min-h-screen grid-cols-[278px_minmax(0,1fr)] bg-app-canvas text-app-ink max-chat:block',
  backdrop: 'hidden',
  backdropOpen:
    'max-chat:fixed max-chat:inset-0 max-chat:z-[2] max-chat:block max-chat:border-0 max-chat:bg-[rgb(10_15_32_/_38%)]',
  sidebar: classes(
    'flex flex-col gap-[1.35rem] px-5 py-[1.6rem]',
    'bg-[linear-gradient(180deg,#1c2342_0%,#11162e_100%)] text-[#e9edff]',
    'max-chat:fixed max-chat:inset-y-0 max-chat:left-0 max-chat:z-[3] max-chat:w-[min(278px,86vw)]',
    'max-chat:shadow-[12px_0_40px_rgb(11_17_40_/_25%)]',
    'max-chat:transition-transform max-chat:duration-[180ms] max-chat:ease-[ease]',
  ),
  sidebarOpen: 'max-chat:translate-x-0',
  sidebarClosed: 'max-chat:translate-x-[-102%]',
  brand: 'flex items-center gap-3 border-b border-[rgb(219_227_255_/_12%)] px-[0.35rem] pt-1 pb-5',
  brandMark:
    'grid size-[2.35rem] place-items-center rounded-[0.8rem] bg-brand-accent text-[1.25rem] font-black text-[#17203d]',
  brandTitle: 'block text-[1.12rem] tracking-[-0.04em]',
  brandSubtitle: 'mt-[0.2rem] block text-[0.72rem] text-[#a7b1d4]',
  sidebarActions: 'grid gap-[0.6rem]',
  newConversationButton: classes(
    'cursor-pointer rounded-[0.85rem] border px-4 py-[0.85rem] text-left font-extrabold',
    'border-[rgb(185_232_143_/_30%)] bg-brand-accent text-[#16203e]',
  ),
  newConversationIcon: 'mr-[0.45rem] text-[1.2rem] align-[-0.05em]',
  sampleButton: classes(
    'cursor-pointer rounded-[0.85rem] border px-4 py-[0.78rem] text-left font-bold no-underline',
    'border-[rgb(200_208_235_/_18%)] bg-[rgb(113_128_197_/_12%)] text-[#d9dff4]',
    'hover:bg-[rgb(113_128_197_/_28%)] hover:text-white',
  ),
  sampleButtonIcon: 'mr-[0.45rem]',
  popularQuestions: 'grid gap-2',
  sidebarSectionTitle:
    'mt-0 mb-1 text-[0.72rem] font-extrabold tracking-[0.1em] text-[#8995bd] uppercase',
  popularQuestionButton: classes(
    'cursor-pointer rounded-[0.65rem] border-0 px-3 py-[0.68rem] text-left text-[0.78rem]',
    'bg-[rgb(113_128_197_/_12%)] text-[#c8d0eb]',
    'transition-colors duration-[160ms] hover:bg-[rgb(113_128_197_/_28%)] hover:text-white',
  ),
  dataSummary: 'grid grid-cols-2 gap-[0.6rem] pt-[0.4rem]',
  dataSummaryTitle:
    'col-span-2 mt-0 mb-1 text-[0.72rem] font-extrabold tracking-[0.1em] text-[#8995bd] uppercase',
  dataSummaryCard: 'rounded-[0.7rem] bg-[rgb(113_128_197_/_12%)] p-3',
  dataSummaryValue: 'block text-[1.2rem] text-brand-accent',
  dataSummaryLabel: 'mt-[0.18rem] block text-[0.7rem] text-[#a7b1d4]',
  sidebarFooter: 'mt-auto mb-0 text-[0.72rem] leading-[1.55] text-[#7783a9]',
  workspace:
    'grid min-h-screen min-w-0 grid-rows-[auto_minmax(0,1fr)_auto] max-chat:min-h-svh',
  header: classes(
    'flex items-center justify-between gap-4 px-[clamp(1.25rem,5vw,4.5rem)] py-6',
    'border-b border-[#e8ecf7] bg-[rgb(255_255_255_/_82%)]',
    'max-chat:p-4',
  ),
  menuButton:
    'hidden cursor-pointer border-0 bg-transparent text-[1.35rem] text-[#536087] max-chat:block',
  headerEyebrow:
    'mt-0 mb-1 text-[0.7rem] font-extrabold tracking-[0.12em] text-[#6471a0] uppercase',
  headerTitle: 'm-0 text-[1.25rem] font-bold tracking-[-0.04em] text-[#151d3a]',
  sourceBadge: classes(
    'whitespace-nowrap rounded-full border px-[0.7rem] py-[0.45rem] text-[0.72rem] font-bold',
    'border-[#dfe4ef] bg-[#f5f7fb] text-[#536087] max-chat:text-[0.62rem]',
  ),
  timeline: classes(
    'mx-auto w-[min(860px,calc(100%_-_2rem))] overflow-y-auto pt-10 pb-8',
    'max-chat:w-[calc(100%_-_1.2rem)] max-chat:pt-6',
  ),
  messageRow: 'mb-[1.8rem] flex gap-3',
  userMessageRow: 'justify-end',
  assistantAvatar:
    'grid size-8 shrink-0 place-items-center self-start rounded-[0.7rem] bg-brand-accent font-black text-app-ink',
  messageContent: 'max-w-[min(700px,90%)] max-chat:max-w-[88%]',
  messageBubble:
    'px-[1.1rem] py-4 leading-[1.65] shadow-[0_10px_30px_rgb(47_67_129_/_7%)]',
  userMessageBubble: 'rounded-[1rem_1rem_0.25rem_1rem] bg-brand-primary text-white',
  assistantMessageBubble: 'rounded-[1rem_1rem_1rem_0.25rem] bg-white text-[#293454]',
  suggestedQuestions: 'mt-[0.85rem] flex flex-wrap gap-2',
  suggestedQuestionButton: classes(
    'cursor-pointer rounded-full border bg-white px-[0.78rem] py-[0.6rem] text-left text-[0.78rem]',
    'border-[#dfe4f2] text-[#536087] hover:border-[#7774d7] hover:text-[#504ebd]',
  ),
  programList: 'mt-[0.9rem] grid gap-[0.8rem]',
  searchingBubble: classes(
    'rounded-[1rem_1rem_1rem_0.25rem] bg-white px-[1.1rem] py-4 leading-[1.65]',
    'text-[#6d7898] shadow-[0_10px_30px_rgb(47_67_129_/_7%)]',
  ),
  composer:
    'relative mx-auto w-[min(860px,calc(100%_-_2rem))] pb-6 max-chat:w-[calc(100%_-_1.2rem)]',
  searchError: classes(
    'mt-0 mb-[0.55rem] rounded-[0.7rem] border px-[0.8rem] py-[0.65rem] text-[0.76rem]',
    'border-[#f0cfd4] bg-[#fff5f6] text-[#9a3947]',
  ),
  composerInput: classes(
    'min-h-[3.25rem] w-full resize-none rounded-2xl border bg-white',
    'pt-[0.95rem] pr-[3.4rem] pb-[1.35rem] pl-4 text-[#1b2544]',
    'border-[#d7dcef] shadow-[0_10px_28px_rgb(47_67_129_/_7%)] outline-0',
    'focus:border-[#7774d7] focus:shadow-[0_0_0_3px_rgb(119_116_215_/_15%)]',
  ),
  submitButton: classes(
    'absolute top-[0.65rem] right-[0.65rem] grid size-[2.15rem] place-items-center',
    'cursor-pointer rounded-[0.7rem] border-0 bg-brand-primary text-[1.2rem] text-white',
    'disabled:cursor-not-allowed disabled:opacity-[0.35]',
  ),
  composerHint: 'mt-[0.45rem] ml-[0.35rem] block text-[0.68rem] text-[#8a94ae]',
  programCard:
    'rounded-2xl border border-[#e4e8f5] bg-white p-[1.1rem] shadow-[0_12px_30px_rgb(47_67_129_/_7%)]',
  programCardHeader: 'flex items-center justify-between gap-3',
  programTag:
    'rounded-[0.35rem] bg-[#f0f9e9] px-[0.48rem] py-1 text-[0.68rem] font-extrabold text-[#536d37]',
  programDeadline: 'text-[0.74rem] font-extrabold text-[#b75561]',
  programTitle:
    'mt-3 mb-[0.18rem] text-[1.02rem] font-bold tracking-[-0.025em] text-app-ink',
  programOrganization: 'm-0 text-[0.75rem] text-[#7883a3]',
  programSummary: 'my-3 text-[0.82rem] leading-[1.55] text-[#5c6785]',
  programDetails: classes(
    'flex flex-col items-start justify-between gap-1 rounded-[0.65rem] p-[0.7rem]',
    'bg-[#f7f8fc] text-[0.75rem] text-[#4d597c]',
  ),
  matchedReasons: 'mt-[0.7rem] flex flex-wrap gap-[0.35rem]',
  matchedReason: 'text-[0.7rem] text-[#5c6785]',
  programActions: 'mt-[0.85rem] flex items-center justify-start gap-3',
  programDetailsButton: classes(
    'cursor-pointer rounded-[0.55rem] border-0 px-[0.7rem] py-[0.55rem]',
    'bg-[#5e5fc8] text-[0.74rem] font-extrabold text-white',
  ),
  programSourceLink:
    'rounded-[0.55rem] bg-[#f1f2ff] px-[0.7rem] py-[0.55rem] text-[0.74rem] font-extrabold text-[#5e5fc8] no-underline',
} as const

export function chatBackdropClassName(isOpen: boolean) {
  return isOpen
    ? `${chatPageStyles.backdrop} ${chatPageStyles.backdropOpen}`
    : chatPageStyles.backdrop
}

export function chatSidebarClassName(isOpen: boolean) {
  const visibility = isOpen ? chatPageStyles.sidebarOpen : chatPageStyles.sidebarClosed
  return `${chatPageStyles.sidebar} ${visibility}`
}

export function chatMessageRowClassName(isUser: boolean) {
  return isUser
    ? `${chatPageStyles.messageRow} ${chatPageStyles.userMessageRow}`
    : chatPageStyles.messageRow
}

export function chatMessageBubbleClassName(isUser: boolean) {
  const variant = isUser
    ? chatPageStyles.userMessageBubble
    : chatPageStyles.assistantMessageBubble
  return `${chatPageStyles.messageBubble} ${variant}`
}
