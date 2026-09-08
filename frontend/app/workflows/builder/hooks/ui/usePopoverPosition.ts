import { useState, useRef, useEffect, useMemo } from 'react';
import { clampMenuLeft } from '@/lib/utils/menuPlacement';

/**
 * Position a portalled popover under its trigger button, right-aligned to it.
 *
 * The hook owns the WIDTH as well as the offsets, and hands back one style
 * object for the box: the clamp has to measure the same width the box paints,
 * and while the two were spelled separately (a number here, a `w-72` class
 * there) six of the nine call sites disagreed with their own box by 8 to 20
 * pixels. Spread `popoverStyle` and let the class list carry no width.
 *
 * @param isOpen - Whether the popover is currently open
 * @param width - Width of the popover in PIXELS (e.g., 208, 288, 320). Pixels, not rem: the clamp measures the viewport in pixels, so a rem width would have to be resolved against the root font size to be comparable. The nine call sites carry the pixel value their `w-*` class painted.
 *
 * @returns Object with:
 *   - buttonRef: Ref to attach to the trigger button
 *   - popoverStyle: `top`/`left`/`width`/`max-width` for the portalled box
 *
 * @example
 * ```tsx
 * const [isOpen, setIsOpen] = useState(false);
 * const { buttonRef, popoverStyle } = usePopoverPosition(isOpen, 288);
 *
 * return (
 *   <>
 *     <button ref={buttonRef} onClick={() => setIsOpen(!isOpen)}>
 *       Info
 *     </button>
 *     {isOpen && ReactDOM.createPortal(
 *       <div className="fixed z-[9999] p-3 ..." style={popoverStyle}>
 *         Popover content
 *       </div>,
 *       document.body
 *     )}
 *   </>
 * );
 * ```
 */
export function usePopoverPosition(isOpen: boolean, width: number) {
  const buttonRef = useRef<HTMLButtonElement>(null);
  const [popoverPosition, setPopoverPosition] = useState({ top: 0, left: 0 });

  useEffect(() => {
    if (isOpen && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      setPopoverPosition({
        top: rect.bottom + 4,
        // Right-aligned to the trigger, then held inside the screen. The old
        // `Math.max(8, ...)` was a lower bound only: on a narrow window the
        // right edge of the popover still ran off the far side.
        left: clampMenuLeft(rect.right - width, width),
      });
    }
  }, [isOpen, width]);

  const popoverStyle = useMemo(
    () => ({
      top: popoverPosition.top,
      left: popoverPosition.left,
      width,
      // A window narrower than the popover has no position that fits it, so the
      // box gives way as well as moving.
      maxWidth: 'calc(100vw - 1rem)',
    }),
    [popoverPosition.top, popoverPosition.left, width],
  );

  return { buttonRef, popoverStyle };
}
