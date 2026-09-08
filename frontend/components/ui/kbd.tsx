import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * A key or key combination, drawn as a keycap.
 *
 * One primitive rather than a class string copied next to every shortcut hint:
 * the app spells its shortcuts in three places (the search field, the home
 * quick-open button, the sidebar's customize menu) and the class string was
 * copied between them.
 *
 * The keycap sits on `--bg-secondary`, one step off the surface behind it. The
 * home button is the exception and overrides it, because that button IS a
 * `--bg-secondary` surface and a keycap the same colour would vanish into it.
 * Where the cap shows is each call site's business (the search field hides it
 * under `sm`, the home button under `md`), so no breakpoint is baked in here.
 */
const Kbd = React.forwardRef<HTMLElement, React.HTMLAttributes<HTMLElement>>(
  ({ className, ...props }, ref) => (
    <kbd
      ref={ref}
      className={cn(
        'pointer-events-none rounded-md border border-theme bg-[var(--bg-secondary)] px-1.5 py-0.5 font-sans text-xs font-medium text-[var(--text-secondary)]',
        className,
      )}
      {...props}
    />
  ),
);
Kbd.displayName = 'Kbd';

export { Kbd };
