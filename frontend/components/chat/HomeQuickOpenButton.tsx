'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown } from 'lucide-react';
import { Link } from '@/i18n/navigation';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/kbd';
import { QUICK_OPEN_ARIA_KEYSHORTCUTS, useQuickOpenShortcutLabel } from '@/lib/sidebar/quickOpenShortcut';
import { useQuickOpenDestination } from '@/lib/sidebar/useQuickOpenShortcut';

/**
 * The home page's quick-open button: it jumps to the page the user picked in
 * the sidebar's customize menu (Agenda unless they changed it), and spells out
 * the keyboard shortcut that does the same thing.
 *
 * It used to be a hardcoded jump to the board drawn as a bare chevron at 40%
 * opacity, its label appearing only on hover - so nothing on screen said where
 * it went, or that it was a control at all. It is now a standard button,
 * shaped and bordered like every other control in the app, that reads its icon
 * and label from the destination and keeps the bouncing chevron as the "there
 * is more below" cue.
 *
 * The LABEL is what is permanent about it. The keycap is not: it appears on
 * hover and on keyboard focus, so the resting page carries the destination and
 * nothing else.
 *
 * The shortcut itself is bound by the app shell, not here: the customize menu
 * spells those keys out wherever the sidebar is, so they have to work there
 * too, not only on the page this button lives on.
 *
 * Desktop-only: on mobile the bottom of the home view belongs to the composer,
 * and the same pages are one tap away in the sidebar.
 */
export function HomeQuickOpenButton() {
  const t = useTranslations('sidebar');
  const item = useQuickOpenDestination();
  const shortcutLabel = useQuickOpenShortcutLabel();

  const Icon = item.icon;
  const label = t(`nav.${item.titleKey}`);

  return (
    <Button
      asChild
      variant="secondary"
      size="sm"
      className="group absolute bottom-6 left-1/2 z-20 hidden -translate-x-1/2 shadow-sm sm:inline-flex"
    >
      <Link
        href={item.path}
        title={t('customize.shortcutTitle', { label, keys: shortcutLabel })}
        aria-label={label}
        aria-keyshortcuts={QUICK_OPEN_ARIA_KEYSHORTCUTS}
        data-testid="home-quick-open"
      >
        <Icon className="h-4 w-4 shrink-0" />
        <span className="whitespace-nowrap text-sm">{label}</span>
        {/* Announced through `aria-keyshortcuts` above, so the keycap itself is
            decoration and would only be read out twice.

            Shown on hover and on keyboard focus, not at rest. At rest the
            button has one job: say where it goes. The keycap is the answer to a
            second question ("and without the mouse?") that nobody asks until
            they are already pointing at the control, and spelling it out
            permanently made the one button on the home page a third wider and
            noisier than the page it sits on. The keys keep working from
            anywhere either way, and the customize menu still lists them.

            `group-focus-visible`, not the `group-focus-within` this codebase
            uses elsewhere: the group here IS the anchor and nothing inside it
            is focusable, so `within` would only ever fire for the anchor
            anyway, and `focus-visible` additionally spares a mouse user the
            keycap appearing after a click. The button does grow when it
            appears; it is centred by `-translate-x-1/2`, so it grows evenly
            about its own middle rather than shunting the page. */}
        <Kbd
          aria-hidden="true"
          data-testid="home-quick-open-shortcut"
          // This button IS a `--bg-secondary` surface, so the keycap steps the
          // other way to stay visible on it.
          className="hidden bg-[var(--bg-primary)] md:group-hover:inline-block md:group-focus-visible:inline-block"
        >
          {shortcutLabel}
        </Kbd>
        <ChevronDown className="h-4 w-4 shrink-0 animate-bounce group-hover:animate-none" />
      </Link>
    </Button>
  );
}
