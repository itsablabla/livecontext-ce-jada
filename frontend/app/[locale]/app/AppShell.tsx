'use client';

import React from 'react';
import { cn } from '@/lib/utils';
import { AppSidebar } from '@/components/app/AppSidebar';
import { AppHeader } from '@/components/app/AppHeader';
import { SidePanel } from '@/components/app/SidePanel';
import { ConversationActivityProvider } from '@/contexts/ConversationActivityContext';
import { useSidePanelLayoutSafe } from '@/contexts/SidePanelLayoutContext';
import { useQuickOpenShortcut } from '@/lib/sidebar/useQuickOpenShortcut';

/** Header + routed page content: the primary column, to the right of the sidebar. */
function MainContentColumn({ children }: { children: React.ReactNode }) {
  // The activity provider wraps header + content so the header toggle and the
  // conversation activity card share open/closed state.
  return (
    <ConversationActivityProvider>
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        <AppHeader />
        <main className="flex-1 flex flex-col min-h-0 relative overflow-hidden">
          {children}
        </main>
      </div>
    </ConversationActivityProvider>
  );
}

/**
 * App shell: sidebar + main content + the unified side panel, arranged per the user's
 * dock-position preference (Settings > Preferences, org-aware):
 *   - 'right'       -> row root: [sidebar][ content | panel ]. Content shrinks in width.
 *   - 'bottom'      -> row root, content region is a column: the panel docks under the
 *                      content spanning only the content area (right of the sidebar).
 *                      Content shrinks in height.
 *   - 'bottom-full' -> column root: [ row(sidebar + content) ][ panel ]. The panel spans
 *                      the FULL viewport width (edge to edge, under the sidebar too) and
 *                      the sidebar shrinks vertically to sit above it.
 *   - 'floating'    -> DETACHED: arranged like the dock it was detached FROM, deliberately.
 *                      The panel positions itself `fixed` in that mode, so it takes no
 *                      layout space and renders identically from either branch - but which
 *                      branch it renders from decides whether detaching REMOUNTS it. The
 *                      two branches put different components at the same child positions,
 *                      so React tears the subtree down when the branch changes, and with it
 *                      everything the panel holds: a running canvas, an SSE stream, an
 *                      interface iframe. Following `lastDock` is what keeps a detach a pure
 *                      mode flip from every dock, including the full-width bottom one -
 *                      which is the DEFAULT bottom variant, so pinning 'floating' to the
 *                      'right' branch broke exactly the common case.
 *
 * The panel is mounted in exactly ONE place per mode (inside the content region, or at
 * the root for 'bottom-full'); its tab state lives in SidePanelContext, so switching
 * modes does not lose open tabs. On mobile the panel is a fixed overlay, so these
 * arrangements are inert there.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const { position, lastDock } = useSidePanelLayoutSafe();
  // Bound here rather than on the home button that also carries it: the
  // customize menu spells these keys out wherever the sidebar is, and the
  // sidebar has no call site outside this shell. Called before the branch below
  // so the arrangement cannot decide whether the shortcut exists.
  useQuickOpenShortcut();
  // A detached panel keeps the arrangement of the dock it came from (see above).
  const arrangement = position === 'floating' ? lastDock : position;

  if (arrangement === 'bottom-full') {
    return (
      <div className="flex flex-col h-full relative">
        <div className="flex flex-1 min-h-0 relative">
          <AppSidebar />
          <div className="flex-1 flex flex-row min-w-0 min-h-0">
            <MainContentColumn>{children}</MainContentColumn>
          </div>
        </div>
        {/* Full-width side panel docked under the whole row (sidebar included). */}
        <SidePanel />
      </div>
    );
  }

  // 'right' and 'bottom': the panel lives inside the content region, so it spans only
  // the area to the right of the sidebar. Row for 'right', column for 'bottom'. A
  // detached panel is fixed and out of flow, so either arrangement renders it the
  // same - it is here only to avoid changing branch, and remounting, on the flip.
  const isBottom = arrangement === 'bottom';
  return (
    <div className="flex h-full relative">
      <AppSidebar />
      <div className={cn('flex-1 flex min-w-0 min-h-0', isBottom ? 'flex-col' : 'flex-row')}>
        <MainContentColumn>{children}</MainContentColumn>
        <SidePanel />
      </div>
    </div>
  );
}
