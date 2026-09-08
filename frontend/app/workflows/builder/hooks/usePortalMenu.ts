'use client';

import * as React from 'react';
import { clampMenuCenter } from '@/lib/utils/menuPlacement';

/** Where the menu hangs relative to its trigger button. */
export type PortalMenuPlacement = 'below' | 'above';

export interface UsePortalMenuResult {
  /** Menu open state (independent of whether it can be portaled yet). */
  open: boolean;
  /** True once the menu should actually be portaled (mounted + open + anchored). */
  isVisible: boolean;
  /** Click handler for the trigger button - opens, re-anchors, or closes. */
  toggle: (event?: React.MouseEvent) => void;
  /** Imperative close (e.g. after picking an item). */
  close: () => void;
  triggerRef: React.RefObject<HTMLButtonElement | null>;
  menuRef: React.RefObject<HTMLDivElement | null>;
  /** Absolute positioning for the portaled menu container. */
  menuStyle: React.CSSProperties;
}

/**
 * Portal-menu plumbing shared by the canvas's small dropdown menus (the trigger
 * node launcher, the run-mode toolbar trigger picker).
 *
 * <p>The canvas is draggable and lives inside clipping parents, so these menus
 * are portaled to the body and positioned from the trigger's client rect rather
 * than laid out in flow. Dismissal listens on the CAPTURE phase: React Flow's
 * pane handlers stop propagation, so a bubbling document listener would never
 * see a click on the canvas and the menu would stay stuck open.
 */
/**
 * @param placement which side of the trigger the menu hangs from
 * @param menuWidth the menu's width in px. Optional: given, the centre is held
 *   far enough from both edges for the whole menu to stay on screen; omitted,
 *   the menu is centred on the trigger exactly as before.
 */
export function usePortalMenu(
  placement: PortalMenuPlacement = 'below',
  menuWidth?: number,
): UsePortalMenuResult {
  const [open, setOpen] = React.useState(false);
  const [anchor, setAnchor] = React.useState<{ left: number; top: number } | null>(null);
  const [mounted, setMounted] = React.useState(false);
  const triggerRef = React.useRef<HTMLButtonElement | null>(null);
  const menuRef = React.useRef<HTMLDivElement | null>(null);

  React.useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  const close = React.useCallback(() => setOpen(false), []);

  // Re-anchors on every open: the trigger moves with the canvas, so a rect
  // captured once would drift. Reads `open` directly rather than from a state
  // updater, which must stay pure (it runs twice under StrictMode).
  const toggle = React.useCallback((event?: React.MouseEvent) => {
    event?.stopPropagation();
    const trigger = triggerRef.current;
    if (!trigger) return;
    if (open) { setOpen(false); return; }
    const rect = trigger.getBoundingClientRect();
    const center = rect.left + rect.width / 2;
    setAnchor({
      left: menuWidth ? clampMenuCenter(center, menuWidth) : center,
      top: placement === 'above' ? rect.top - 6 : rect.bottom + 6,
    });
    setOpen(true);
  }, [open, placement, menuWidth]);

  React.useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: Event) => {
      const target = event.target as Node;
      if (triggerRef.current?.contains(target)) return;
      if (menuRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    const onDismiss = () => setOpen(false);
    document.addEventListener('mousedown', onPointerDown, true);
    document.addEventListener('pointerdown', onPointerDown, true);
    document.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onDismiss, true);
    window.addEventListener('resize', onDismiss);
    return () => {
      document.removeEventListener('mousedown', onPointerDown, true);
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onDismiss, true);
      window.removeEventListener('resize', onDismiss);
    };
  }, [open]);

  return {
    open,
    isVisible: mounted && open && anchor !== null,
    toggle,
    close,
    triggerRef,
    menuRef,
    menuStyle: {
      left: anchor?.left ?? 0,
      top: anchor?.top ?? 0,
      transform: placement === 'above' ? 'translate(-50%, -100%)' : 'translateX(-50%)',
    },
  };
}
