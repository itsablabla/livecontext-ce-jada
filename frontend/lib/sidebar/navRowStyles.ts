/**
 * The shape of one sidebar navigation row, in one place.
 *
 * Read by every row in the expanded panel's navigation block: the fixed Home
 * and Marketplace rows, the pages the user kept (ConversationSidebar) and the
 * overflow row that ends the block (SidebarMoreMenu).
 *
 * Shared rather than copied because it DRIFTED, visibly. The overflow row was
 * built on `<Button>`, whose base style carries `gap-2`; its icon also carried
 * the rows' own `mr-2`, so the two stacked and the word "More" sat 8px to the
 * right of "Files", "Tables" and every other label above it. A row is defined
 * by these strings now, so a row cannot be styled almost like a row.
 *
 * `group` is part of the contract: the icon and label classes below both react
 * to the row's hover and to its active `bg-surface-hover`.
 */
const BASE =
  'flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ' +
  // No `whitespace-nowrap` here, deliberately. `<Button>` used to give the
  // overflow row one, and keeping it looked like the reason a row stays one
  // line - but the only text in a row is the label, and the label's own
  // `truncate` already declares `white-space: nowrap` on the element that
  // matters. A row-level copy would change nothing and would read as load-
  // bearing; SIDEBAR_NAV_ROW_LABEL_CLASS is where that behaviour lives.
  //
  // Keyboard focus has to be visible on a row too. The overflow row used to get
  // this from the button system and the page rows never had it at all, so
  // tabbing through the block went visibly dark for seven rows and then lit up
  // on the eighth. One ring, on every row.
  //
  // The RING is the button system's, deliberately: the same accent, the same
  // 2px, so focus reads the same here as anywhere else in the app. The OFFSET
  // COLOUR is not, and must not be. `ring-offset` paints a 1px band of that
  // colour between the row and the ring, so it has to be the colour of what the
  // row actually sits on. These rows sit on the sidebar, which is
  // `bg-theme-secondary`; copying the button system's `--bg-primary` (what a
  // button sits on out in the page) drew a white hairline halo on a grey panel
  // in light theme, and a near-black one on dark grey in dark theme.
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] ' +
  'focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-secondary)]';

/** The row itself. Active rows keep the hover surface painted. */
export function sidebarNavRowClass(isActive: boolean): string {
  return `${BASE} ${isActive ? 'bg-surface-hover' : 'bg-transparent hover:bg-surface-hover'}`;
}

/**
 * The 16px icon that opens the row, 8px clear of the label.
 *
 * `shrink-0` because the row is a flex line: without it a label long enough to
 * fill the sidebar squeezes the icon narrower, and the block's icons stop
 * lining up in exactly the locale where the labels are longest.
 */
export const SIDEBAR_NAV_ROW_ICON_CLASS =
  'w-4 h-4 shrink-0 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors';

/**
 * The label, which warms and thickens with the row's state.
 *
 * `truncate min-w-0` is what keeps a row to one line, and it is the ONLY thing
 * that does: `truncate` declares `white-space: nowrap` plus an ellipsis right
 * on the text. Both halves are needed. Without `truncate` a long label wraps
 * and that row grows taller than its neighbours; with `truncate` but without
 * `min-w-0` the label never shrinks (a flex item's `min-width` defaults to its
 * content), so the ellipsis never fires and the sidebar's `overflow-hidden`
 * slices the word at the panel edge with nothing to say it was cut. This is the
 * same pair the conversation and project titles in this sidebar already use.
 */
export const SIDEBAR_NAV_ROW_LABEL_CLASS =
  'text-sm truncate min-w-0 text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors';
