'use client';

import * as React from 'react';
import Link from 'next/link';
import { Lock } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';

/**
 * Two halves of one message: WHICH models cannot be paid for, and WHAT TO DO.
 *
 * <p><b>Why they are separate.</b> On the Free plan the monthly credits pay for
 * running workflow nodes and nothing else, so choosing a model for a chat, an
 * agent or a generation leads to a refusal that lands after the choice, against
 * a balance the reader can see is not zero. Saying so while they choose costs
 * nothing. But the marker sits inside Radix `Select` options on six of the
 * surfaces, and an option is the one place a control cannot go (the composer's
 * own menu is plain divs, so it is the pickers that set the constraint, and one
 * marker serves both):
 *
 * <ul>
 *   <li>ARIA gives {@code role="option"} presentational children, so a nested
 *       {@code role="button"} is stripped and announced to nobody.</li>
 *   <li>A Radix listbox traps focus and drives itself with the arrow keys, so a
 *       {@code tabIndex} inside it is never reached, and Enter selects the
 *       option rather than pressing what is inside it.</li>
 *   <li>Radix re-renders the SELECTED option's content inside the trigger
 *       {@code <button>}, so whatever is here ends up inside a button too, and
 *       a {@code <span>} is the one shape valid there. (The row it sits in is
 *       still a {@code <div>}, which predates this and is not made worse by
 *       adding a second one.)</li>
 * </ul>
 *
 * <p>So {@link UpgradeRequiredBadge} is a LABEL: a lock, plus a word that only a
 * screen reader hears, because the row is already carrying tier, capabilities,
 * context and price and has no width to spare. The way to act lives in
 * {@link UpgradeRequiredNotice}, under the control rather than inside it.
 *
 * <p><b>And it navigates rather than opening a dialog.</b> Both live inside
 * floating hosts (the composer menu at z-[10000], the composer Options panel at
 * z-[99999]) that paint ABOVE a centred dialog, so a dialog opened from in here
 * would appear underneath the very menu it was opened from. The same two hosts
 * are why {@code NoProviderCta} links out instead of opening one. The plans
 * page carries both answers anyway: subscribe, or buy a one-off top-up.
 *
 * <p>It opens NO dialog of its own. The plan comparison used to be reachable
 * from here; it belongs to the pricing page now, which this badge's link leads
 * to, so the badge is text and a link and can sit under any host at any z.
 *
 * <p>Neither renders unless it applies: not on CE, not on a paid plan, not for
 * a Free account that has topped up, and not while the balance is in flight.
 * Both take the verdict as a PROP, so a picker asks the question once for its
 * whole list instead of once per row, and a presentational row stays renderable
 * without a query client behind it.
 */

export interface UpgradeRequiredBadgeProps {
  /** The verdict from `useMonthlyCreditsCannotPay`, asked once by the caller. */
  blocked: boolean;
  className?: string;
}

/**
 * The verdict is checked by a component that calls NO hook, so a surface where
 * nothing is blocked mounts nothing at all: no translation lookup, and no
 * requirement that a picker used in a bare test be wrapped in an intl provider
 * to render a row it was never going to show.
 */
export function UpgradeRequiredBadge({ blocked, ...rest }: UpgradeRequiredBadgeProps) {
  if (!blocked) return null;
  return <UpgradeBadgeBody {...rest} />;
}

function UpgradeBadgeBody({ className }: Omit<UpgradeRequiredBadgeProps, 'blocked'>) {
  const t = useTranslations('billing.upgradeRequired');

  return (
    <span
      // A label, not a control: no role and no handlers. The word is read out
      // as part of the option's own name, which is how it reaches a screen
      // reader at all, while the row keeps its width for the model's facts.
      title={t('title')}
      // The lock alone, in amber. The pill it used to sit in competed with the
      // tier and provider badges beside it, on a row that already carries five
      // pieces of information: a second filled shape read as another category
      // rather than as a warning about this one.
      className={cn('inline-flex items-center align-middle', className)}
    >
      <Lock className="h-3 w-3 flex-shrink-0 text-amber-600 dark:text-amber-400" aria-hidden />
      <span className="sr-only">{t('label')}</span>
    </span>
  );
}

export interface UpgradeRequiredNoticeProps {
  /** The verdict from `useMonthlyCreditsCannotPay`, asked once by the caller. */
  blocked: boolean;
  className?: string;
}

/**
 * The reason and the way out, under the control the badge marks.
 *
 * <p>An ordinary link, reachable by Tab and unaffected by whatever floating
 * panel it happens to be sitting in.
 */
export function UpgradeRequiredNotice({ blocked, ...rest }: UpgradeRequiredNoticeProps) {
  if (!blocked) return null;
  return <UpgradeNoticeBody {...rest} />;
}

function UpgradeNoticeBody({ className }: Omit<UpgradeRequiredNoticeProps, 'blocked'>) {
  const t = useTranslations('billing.upgradeRequired');
  const locale = useLocale();

  return (
    <div
      // Announced when it appears: the balance arrives after the first paint,
      // so the row silently grows a lock a moment after the menu opens.
      role="status"
      className={cn('flex items-start gap-2 text-xs text-theme-secondary', className)}
    >
      <Lock className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-amber-600 dark:text-amber-400" aria-hidden />
      <p className="min-w-0">
        {t('explanation')}{' '}
        <Link
          href={`/${locale}/app/settings/pricing`}
          className="font-medium text-[var(--accent-primary)] underline underline-offset-2"
        >
          {t('cta')}
        </Link>
      </p>
    </div>
  );
}
