function classes(...groups: string[]) {
  return groups.join(' ')
}

export const supportProgramDetailStyles = {
  page: 'mx-auto w-[min(920px,calc(100%_-_2rem))] py-[clamp(1.5rem,5vw,4rem)]',
  unavailablePage: 'mx-auto w-[min(720px,calc(100%_-_2rem))] py-[clamp(1.5rem,5vw,4rem)]',
  header: 'mb-8 flex items-center justify-between gap-4',
  backLink: classes(
    'inline-flex items-center rounded-[0.7rem] border px-[0.85rem] py-[0.65rem]',
    'border-[#d8e0f3] bg-white text-[0.85rem] font-bold text-[#43527a] no-underline',
    'hover:border-[#5e5fc8] hover:text-[#5e5fc8]',
  ),
  sourceBadge: 'rounded-full bg-[#f0f2ff] px-3 py-[0.45rem] text-[0.72rem] font-extrabold text-[#5e5fc8]',
  hero: classes(
    'mb-6 grid grid-cols-[minmax(0,1fr)_minmax(160px,200px)] items-start gap-6 rounded-3xl',
    'bg-white p-[clamp(1.4rem,4vw,2.5rem)] shadow-[0_16px_42px_rgb(47_67_129_/_8%)]',
    'max-chat:grid-cols-1',
  ),
  eyebrow:
    'mt-0 mb-2 text-[0.72rem] font-extrabold tracking-[0.12em] text-[#6471a0] uppercase',
  title: 'm-0 text-[clamp(1.65rem,4vw,2.45rem)] font-bold leading-[1.25] tracking-[-0.045em] text-app-ink',
  organization: 'mt-3 mb-0 text-[0.9rem] font-bold text-[#6471a0]',
  summary: 'mt-5 mb-0 leading-[1.7] text-[#536087]',
  statusCard: 'grid gap-2 rounded-2xl bg-[#f6f8ff] p-5 text-left',
  statusLabel: 'text-[0.72rem] font-extrabold tracking-[0.08em] text-[#6471a0] uppercase',
  statusValue: 'text-[1.2rem] text-[#26305a]',
  score: 'mt-1 w-fit rounded-md bg-[#edf8e5] px-2 py-1 text-[0.72rem] font-extrabold text-[#536d37]',
  details: 'grid grid-cols-2 gap-3 max-chat:grid-cols-1',
  detailItem: 'rounded-2xl border border-[#e1e6f4] bg-white p-5',
  detailLabel: 'mt-0 mb-3 text-[0.75rem] font-extrabold tracking-[0.08em] text-[#6471a0] uppercase',
  detailValue: 'leading-[1.6] text-[#26305a]',
  tagList: 'm-0 flex list-none flex-wrap gap-2 p-0',
  tag: 'rounded-full bg-[#f0f2ff] px-3 py-1 text-[0.78rem] font-bold text-[#5e5fc8]',
  emptyValue: 'text-[#7b86a3]',
  reasonSection: 'mt-6 rounded-3xl border border-[#e1e6f4] bg-white p-[clamp(1.4rem,4vw,2.1rem)]',
  sectionEyebrow:
    'mt-0 mb-2 text-[0.72rem] font-extrabold tracking-[0.12em] text-[#6471a0] uppercase',
  sectionTitle: 'm-0 text-[1.25rem] font-bold tracking-[-0.03em] text-app-ink',
  reasonList: 'mt-5 mb-0 grid list-none gap-2 p-0',
  reason: 'rounded-xl bg-[#f7f8fc] px-4 py-3 text-[0.88rem] text-[#455276]',
  emptyReason: 'mt-4 mb-0 text-[#6d7898]',
  sourceSection: classes(
    'mt-6 flex items-center justify-between gap-6 rounded-3xl bg-[#1c2342] p-[clamp(1.4rem,4vw,2.1rem)]',
    'text-[#e9edff] max-chat:items-start max-chat:flex-col',
  ),
  sourceEyebrow:
    'mt-0 mb-2 text-[0.72rem] font-extrabold tracking-[0.12em] text-[#aeb9dc] uppercase',
  sourceTitle: 'm-0 text-[1.25rem] font-bold tracking-[-0.03em] text-white',
  sourceDescription: 'mt-3 mb-0 leading-[1.6] text-[#b9c3e3]',
  sourceLink: classes(
    'shrink-0 rounded-[0.75rem] bg-brand-accent px-4 py-3 text-[0.84rem] font-extrabold',
    'text-[#17203d] no-underline hover:bg-[#d0efa9]',
  ),
  unavailableCard: 'mt-6 rounded-3xl bg-white p-[clamp(1.5rem,5vw,3rem)] shadow-[0_16px_42px_rgb(47_67_129_/_8%)]',
  unavailableDescription: 'mt-4 mb-0 leading-[1.65] text-[#536087]',
} as const
