'use client';

/**
 * Credit-balance display kit: a progress RING (drawn around the sidebar avatar)
 * and the BALANCE PANEL at the top of the user menu it opens (Total / Remaining
 * + an allowance gauge).
 *
 * Two states, one geometry:
 *
 *  - Under allowance - neutral ink fill, ring and bar both showing the consumed
 *    share of the cycle grant. "2%" on a 10,000-credit plan with 9,779 left.
 *  - Over allowance - carry-over or a PAYG top-up has pushed the wallet above
 *    the cycle grant. The bar turns gold across its full width and the bright
 *    fill measures the SURPLUS (capped at 100%, since a bar cannot draw past
 *    full), with the true figure spelled out as "+X%".
 *
 * The two states must be distinguishable WITHOUT colour, because they are near
 * opposites: an exhausted wallet and a wallet at double its grant both fill the
 * dial completely. So the ring's glyph differs too - a percentage when credits
 * are being consumed, a bare "+" when the wallet is above its grant. Colour is
 * the reinforcement, never the only signal.
 *
 * The gold itself comes from `--credit-gold-*` in globals.css rather than a
 * literal, because it needs DIFFERENT values per theme: the bright metal that
 * reads on the dark ground sits at 1.8:1 on white and would fail WCAG AA for
 * the "+X% over your plan" label.
 */

import React, { useId } from 'react';
import { useLocale, useTranslations } from 'next-intl';
// The app's locale-aware Link: it prefixes the URL for the reader's locale and
// prefetches, both of which a hand-rolled anchor would have to redo by hand.
import { Link } from '@/i18n/navigation';
import { formatCreditsCompact } from '@/lib/format-cost';
import type { CreditGauge } from '@/lib/billing/credit-allowance';

/**
 * The one accessible label for every credit trigger.
 *
 * It lived twice, copied verbatim between two credit triggers down to its
 * comments, which is how they drifted apart before (one localised its
 * percentage, the other did not) and how a third surface would drift again.
 *
 * `amountVisible` is the WCAG 2.5.3 "Label in Name" half. An aria-label REPLACES
 * a control's contents for the accessible name, so where the visible content is
 * the compact amount ("9.8K") the name has to contain that exact string, or a
 * speech-input user saying "click 9.8K" cannot activate it. Where the visible
 * content is the ring's own "2%" or gold "+", the sentence already contains it
 * and prefixing the amount would name something nobody can see.
 */
export function useCreditTriggerLabel({
  balance,
  allowance,
  gauge,
  amountVisible,
}: {
  balance: number | null;
  allowance: number | null;
  gauge: CreditGauge;
  amountVisible: boolean;
}): string {
  const t = useTranslations('billing.balance');
  const locale = useLocale();

  // A surplus that rounds to zero must not announce "+0% over your plan", which
  // contradicts the gold state it appears in.
  //
  // Every percentage is formatted for the APP locale before interpolation: a
  // bare ICU `{percent}` given a number is NOT locale-formatted, so a 3,400%
  // surplus reached this string as "3400" while the panel one click away, which
  // does format it, said "3.400" to the same German reader.
  const compact = compactCredits(balance, locale);

  const detail =
    allowance === null
      // The COMPACT amount here, not the grouped one: on this branch the amount
      // is itself the trigger's visible text, so spelling it a second way would
      // put the same number in the name twice, in two different notations.
      ? t('remainingAmount', { amount: compact })
      : gauge.isOver
        ? gauge.overPct === 0
          ? t('overAllowanceTiny')
          : t('overAllowance', { percent: gauge.overPct.toLocaleString(locale) })
        : t('usedPercent', { percent: gauge.fillPct.toLocaleString(locale) });

  // The no-allowance sentence already opens with the visible string, so
  // prefixing it again would just repeat it.
  if (!amountVisible || allowance === null) return detail;
  return t('triggerLabel', { amount: compact, detail });
}

/**
 * The badge's amount. Two regimes on purpose:
 *
 * At or above 1,000 the shared {@link formatCreditsCompact} abbreviation is what
 * fits ("9.8K" / "9,8K"), and the K is a unit rather than part of the number.
 *
 * Below it the value is a plain count, and the shared formatter is wrong twice:
 * it pads a whole balance to "980.0" (noise, right next to a ring) and it renders
 * with `toFixed`, always a DOT, while the panel this badge opens formats for the
 * locale - so a French reader would see "980.4" here and "980,4" one hover away,
 * for the same wallet. A real fraction is never rounded off: it is spendable.
 */
export function compactCredits(value: number | null | undefined, locale: string): string {
  if (value === null || value === undefined) return formatCreditsCompact(value, locale);
  // Sign handled here, magnitude delegated: formatCreditsCompact's K/M thresholds
  // are unsigned, so a delinquent -5,000 balance came back "-5000.0" - unabbreviated
  // and wider than the badge it has to fit in.
  //
  // Both branches spell the number for the app locale. The abbreviated one used
  // not to: `toFixed` always emits a dot, so a German reader got "9.8K" - which
  // in German reads as nine thousand eight hundred - beside a panel row saying
  // "9.779" for the same wallet. That was fixed in the shared formatter rather
  // than here, because all three of its call sites are billing surfaces and two
  // of them sit on the same page as this one.
  const sign = value < 0 ? '-' : '';
  const magnitude = Math.abs(value);
  if (magnitude >= 1_000) return sign + formatCreditsCompact(magnitude, locale);
  return exactCredits(value, locale);
}

/**
 * A credit count for the panel's own rows: grouped for the app locale, and
 * carrying at most one decimal so it agrees with the compact badge beside it
 * (980.4 must not read "980" here and "980.4" there). Never rounds a fraction
 * away to a whole number, which would overstate a spendable balance.
 */
function exactCredits(value: number, locale: string): string {
  return value.toLocaleString(locale, { maximumFractionDigits: 1 });
}

/**
 * Circular progress dial. Renders its state inside when there is room (>= 26px),
 * which is what makes it self-explanatory in a crowded bar.
 *
 * `percent` is already clamped by the caller's gauge; clamped again here so a
 * bad prop can never draw an arc longer than the circle.
 */
export function CreditRing({
  percent,
  size = 28,
  strokeWidth = 2.5,
  gold = false,
  showLabel = true,
}: {
  percent: number;
  size?: number;
  strokeWidth?: number;
  gold?: boolean;
  showLabel?: boolean;
}) {
  const safePercent = Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0));
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (safePercent / 100) * circumference;

  return (
    <span
      className="relative inline-flex items-center justify-center"
      style={{ width: size, height: size }}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="-rotate-90" aria-hidden="true">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={strokeWidth}
          className={gold ? undefined : 'stroke-black/15 dark:stroke-white/20'}
          stroke={gold ? 'var(--credit-gold-arc-track)' : undefined}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circumference}`}
          className={gold ? undefined : 'stroke-black dark:stroke-white'}
          stroke={gold ? 'var(--credit-gold-arc)' : undefined}
        />
      </svg>
      {/* The percentage needs ~26px to be legible, but the gold state's single
          "+" does not - and dropping it at badge size is what would make gold
          the ONLY signal on the sidebar, for the one reader who cannot use it. */}
      {showLabel && (gold || size >= 26) && (
        <span
          data-testid="credit-ring-label"
          className="absolute inset-0 flex items-center justify-center font-medium tabular-nums leading-none"
          style={{
            // Three regimes, set by how many glyphs have to fit the ring's hole.
            // The gold "+" is one glyph and reads largest; an ordinary "42%" is
            // three; only "100%" is four and has to step down, and it does so as
            // little as it can - 0.25 put it at 7px, which is the least legible
            // label on the dial in the state that matters most (an empty wallet).
            // It never has to carry the meaning alone: the trigger's aria-label
            // and title state the whole sentence.
            // Floored at 8px. At the rail's 14px ring the gold ratio resolved
            // to 7px - exactly the size the note above rejects for "100%" as
            // the least legible label on the dial. A "+" is one glyph and has
            // room to be bigger, so the floor costs it nothing.
            fontSize: Math.max(8, Math.round(size * (gold ? 0.5 : Math.round(safePercent) >= 100 ? 0.29 : 0.32))),
            color: gold ? 'var(--credit-gold-ink)' : undefined,
          }}
        >
          {/* Deliberately NOT the percentage in the gold state: an ink "100%"
              means the wallet is empty and a gold "100%" would mean it holds
              double its grant. Same glyphs, opposite meanings, separated only by
              a hue - unreadable for anyone who cannot resolve it. The exact
              surplus is in the panel, one hover away. */}
          {gold ? '+' : `${Math.round(safePercent)}%`}
        </span>
      )}
    </span>
  );
}

/**
 * The wallet state drawn AROUND the user's avatar.
 *
 * This is the sidebar's whole indicator: there is no number beside it and
 * nothing in the top bar, so the ring carries the state on its own and is
 * always present - discreet while inside the grant, gold above it. The exact
 * figures live one hover away, in the card.
 *
 * It fills its positioned parent, which is sized to the avatar PLUS the gap,
 * so the ring lives inside normal layout instead of spilling out of it. The
 * earlier version pulled itself out with negative offsets, and the consequence
 * was that no padding on the surrounding button could ever contain it.
 *
 * `pointer-events-none` so it never intercepts the click that opens the user
 * menu underneath.
 *
 * `gap` is the breathing room between the avatar's edge and the ring; the SVG
 * is grown by twice that and pulled back by it on every side, which keeps the
 * ring concentric whatever size the avatar is. At 5px the ring reads as a
 * separate object orbiting the avatar rather than a border drawn on it, which
 * is the whole difference between "the user has a ring" and "the user's photo
 * has a coloured edge".
 */
export function CreditAvatarRing({
  percent,
  gold = false,
  avatarSize,
  gap = 5,
  strokeWidth = 2,
}: {
  percent: number;
  gold?: boolean;
  avatarSize: number;
  gap?: number;
  strokeWidth?: number;
}) {
  const safePercent = Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0));
  const size = avatarSize + gap * 2;
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (safePercent / 100) * circumference;

  // Colons are stripped: React's useId emits ":r0:", and a fragment reference
  // carrying them (`url(#:r0:)`) is not reliably resolved.
  const gradientId = `credit-gold-${useId().replace(/:/g, '')}`;

  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 m-auto -rotate-90"
    >
      {/* The gold arc is stroked with a metallic sweep rather than one colour.
          A flat gold cannot work on a white card: every hue bright enough to
          look like metal falls under the 3:1 a graphical object needs, so a
          single value gets forced down into bronze. Splitting it into deep,
          bright and mid stops keeps the legibility in the deep end and puts the
          shine where light would actually land on a curved surface. */}
      {gold && (
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="var(--credit-gold-metal-1)" />
            <stop offset="55%" stopColor="var(--credit-gold-metal-2)" />
            <stop offset="100%" stopColor="var(--credit-gold-metal-3)" />
          </linearGradient>
        </defs>
      )}
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        strokeWidth={strokeWidth}
        className={gold ? undefined : 'stroke-black/12 dark:stroke-white/15'}
        stroke={gold ? 'var(--credit-gold-arc-track)' : undefined}
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeDasharray={`${dash} ${circumference}`}
        className={gold ? undefined : 'stroke-black/55 dark:stroke-white/70'}
        stroke={gold ? `url(#${gradientId})` : undefined}
      />
    </svg>
  );
}

/**
 * Horizontal allowance bar. Neutral under the grant, gold-on-gold above it,
 * and labelled in BOTH states with the quantity it is actually showing.
 *
 * DIRECTION, and why it differs from `SubscriptionGauge` in BalanceBreakdown:
 * this bar fills by credits CONSUMED, so it agrees with the ring beside it and
 * with the "X% of your plan used" sentence both surfaces state. The wallet
 * card's bar fills by credits REMAINING, and that is correct THERE, because the
 * figure printed directly beside it is "9,779 / 10,000" - a remaining count.
 * Each bar agrees with its own adjacent number, which is the pairing a reader
 * actually makes. Flipping either one to match the other would put it in
 * contradiction with the label it sits next to, so the divergence is kept
 * deliberately rather than unified into a single wrong direction.
 */
export function CreditAllowanceGauge({ gauge }: { gauge: CreditGauge }) {
  const t = useTranslations('billing.balance');
  const locale = useLocale();

  return (
    <div>
      <div
        className="h-1.5 rounded-full overflow-hidden relative"
        // The gold track replaces the neutral one in the over state so the whole
        // bar reads as metal even when the surplus fill is short.
        style={{ background: gauge.isOver ? 'var(--credit-gold-track)' : undefined }}
      >
        {!gauge.isOver && <span className="absolute inset-0 bg-theme-tertiary" aria-hidden="true" />}
        <span
          data-testid="balance-gauge-fill"
          className={`absolute inset-y-0 left-0 rounded-full transition-all ${gauge.isOver ? '' : 'bg-gray-900 dark:bg-white'}`}
          style={{
            width: `${gauge.fillPct}%`,
            background: gauge.isOver
              ? 'linear-gradient(90deg, var(--credit-gold-fill-from) 0%, var(--credit-gold-fill-to) 100%)'
              : undefined,
          }}
        />
      </div>
      {/*
        * ALWAYS labelled, in both states. The bar fills by credits CONSUMED, and
        * it sits directly under a row reading "Remaining 9,779" - so a reader
        * pairing the bar with the number above it reads a 2% bar as "almost
        * nothing left", which is exactly backwards. The sentence used to live
        * only in the trigger's aria-label, i.e. nowhere a sighted user could
        * find it, and the label was rendered only in the gold state. Naming the
        * quantity is what lets the two directions coexist: this bar and the
        * wallet card's (which fills by REMAINING) each now say which one it is.
        */}
      <div
        // An EXPLICIT colour, not inheritance. Its current containers happen to
        // set `text-theme-primary`, so this looked fine - but the component is exported,
        // and rendered anywhere without one it produced dark-on-dark text that
        // no test could see. The gold state's inline colour still wins over this.
        className="mt-1.5 text-sm font-medium text-theme-secondary"
        data-testid="balance-gauge-label"
        style={gauge.isOver ? { color: 'var(--credit-gold-ink)' } : undefined}
      >
        {!gauge.isOver
          ? t('usedPercent', { percent: gauge.fillPct.toLocaleString(locale) })
          : gauge.overPct === 0
            ? t('overAllowanceTiny')
            : t('overAllowance', { percent: gauge.overPct.toLocaleString(locale) })}
      </div>
    </div>
  );
}

/**
 * The balance card body, written once so that any surface showing this wallet
 * describes it identically.
 *
 * Both actions are optional, and a caller that omits one gets no control at all
 * rather than an inert one. The sidebar menu, the only production caller, wires
 * both: it is the sole Upgrade CTA in the cloud chrome, and the sole route to
 * the usage page from that menu.
 *
 * `viewUsage` makes the READOUT itself - the figures plus the gauge - that
 * route, rather than adding a "View usage" link underneath it. The gauge is
 * what a reader points at when they want to know where the credits went, so it
 * is the thing that should answer; a separate text link below it said the same
 * thing a second time, in a place nobody aimed at.
 *
 * A null `allowance` is a real state, not an error: the payer is someone else,
 * or the billing payload did not load. Total and the gauge simply do not render;
 * Remaining does, because it is still true.
 */
export function CreditBalancePanel({
  balance,
  allowance,
  gauge,
  subBalance = null,
  paygBalance = null,
  onUpgrade,
  viewUsage,
}: {
  balance: number | null;
  allowance: number | null;
  gauge: CreditGauge;
  /** Renewal-grant bucket - shown only alongside a non-zero top-up bucket. */
  subBalance?: number | null;
  /** Top-up bucket. A non-zero one is usually WHY the gauge went gold. */
  paygBalance?: number | null;
  onUpgrade?: () => void;
  /**
   * Where the readout leads, as BOTH halves of a link: `href` is what the
   * browser needs (new tab, copy link address, the status bar), `onNavigate`
   * is what the app does on an ordinary click (client-side route + close the
   * menu). One object rather than two props so a caller cannot wire half a
   * link and get a control that looks navigable and is not.
   */
  viewUsage?: { href: string; onNavigate: () => void };
}) {
  const t = useTranslations('billing.balance');
  const tPayg = useTranslations('billing.payg');
  const locale = useLocale();
  // The split is only worth the two extra rows when a top-up actually exists:
  // otherwise "Subscription" would simply restate Remaining. This is also the
  // answer to the gold gauge - a wallet above its grant is normally a top-up.
  const showBuckets = subBalance !== null && paygBalance !== null && paygBalance > 0;

  return (
    <div className="w-full">
      <div className="flex items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-2.5 min-w-0">
          {allowance !== null && (
            <CreditRing percent={gauge.fillPct} size={22} strokeWidth={2} gold={gauge.isOver} showLabel={false} />
          )}
          <span className="text-base font-semibold text-theme-primary truncate">
            {t('title')}
          </span>
        </div>
        {onUpgrade && (
          <button
            type="button"
            onClick={onUpgrade}
            data-testid="balance-upgrade"
            className="flex-shrink-0 text-sm px-3 py-1.5 rounded-lg bg-[var(--accent-primary)] text-[var(--accent-foreground)] hover:bg-[var(--accent-hover)] font-medium transition-colors cursor-pointer"
          >
            {t('upgrade')}
          </button>
        )}
      </div>

      <ReadoutFrame viewUsage={viewUsage} actionLabel={t('viewUsage')}>
        <div className="space-y-1.5 mb-3">
          {allowance !== null && (
            <div className="flex items-start justify-between gap-4 text-sm">
              {/* The number never breaks; the LABEL is what gives way, and it
                  wraps rather than truncating. Truncation was wrong here: the
                  sidebar card is only 228px of content, so German "Monatliches
                  Guthaben" beside "10.000 Credits" already overflows at the
                  ORDINARY tier - it would have ellipsised to "Monatliches ..."
                  for every German user, with no title to recover the text from.
                  Wrapping costs a line and stays readable. `items-start` so the
                  two sides still align on the first line when it does wrap. */}
              <span className="text-theme-secondary min-w-0" data-testid="balance-total-label">
                {t('total')}
              </span>
              <span className="font-semibold text-theme-primary tabular-nums whitespace-nowrap">
                {t('creditsAmount', { amount: allowance.toLocaleString(locale) })}
              </span>
            </div>
          )}
          {/* Same shape as the row above: the number holds, the label wraps.
              Leaving the two adjacent rows to behave differently under identical
              pressure is how one of them ends up looking broken. */}
          <div className="flex items-start justify-between gap-4 text-sm">
            <span className="text-theme-secondary min-w-0" data-testid="balance-remaining-label">{t('remaining')}</span>
            <span className="font-semibold text-theme-primary tabular-nums whitespace-nowrap" data-testid="balance-remaining">
              {balance === null ? '-' : exactCredits(balance, locale)}
            </span>
          </div>
        </div>

        {allowance !== null && <CreditAllowanceGauge gauge={gauge} />}
      </ReadoutFrame>

      {/* Outside the link on purpose: its own section below a rule, and the
          link's hover surface stopping at the rule is what tells a reader where
          the clickable readout ends. */}
      {showBuckets && (
        <div className="mt-3 pt-3 border-t border-theme space-y-1.5">
          <div className="flex items-baseline justify-between gap-4 text-xs">
            <span className="text-theme-muted">{tPayg('breakdown.sub')}</span>
            <span className="text-theme-secondary tabular-nums" data-testid="bucket-sub">
              {compactCredits(subBalance, locale)}
            </span>
          </div>
          <div className="flex items-baseline justify-between gap-4 text-xs">
            <span className="text-theme-muted">{tPayg('breakdown.payg')}</span>
            <span className="text-theme-secondary tabular-nums" data-testid="bucket-payg">
              {compactCredits(paygBalance, locale)}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * The figures + gauge, made the route to the usage page when one is wired.
 *
 * A real link, not a div with `role="link"`: an anchor's content model is
 * transparent, so it may legally wrap these block rows, and it brings what a
 * hand-rolled control cannot - Enter activation, middle-click and cmd-click
 * into a new tab, "copy link address", and a place in a screen reader's link
 * list. That matters here more than usual: this readout is the ONLY route to
 * the usage page from the cloud user menu.
 *
 * It is the app's own `Link`, so the href carries the reader's locale (an
 * unprefixed /app URL is redirected to a hardcoded /en, which would open the
 * English page in the new tab of a French reader). The plain click is still
 * handled here rather than left to the router: that is the path carrying the
 * app's navigation guard, the progress bar, and closing the menu behind it.
 *
 * A plain wrapper when nothing is wired, so a panel with no `viewUsage` keeps
 * exactly the markup it had - no link, no focus stop, no hover surface.
 *
 * The action's name is visually-hidden text INSIDE the link rather than an
 * `aria-label`, because a label REPLACES the contents for the accessible name:
 * "View usage" alone would drop the figures, which are the only thing a
 * sighted user can point at or say. This way the name carries both the action
 * and what it is about. No `title` either - it would be announced on top of a
 * name that already says it, and pop a tooltip over the numbers on any hover.
 */
function ReadoutFrame({
  viewUsage,
  actionLabel,
  children,
}: {
  viewUsage?: { href: string; onNavigate: () => void };
  actionLabel: string;
  children: React.ReactNode;
}) {
  if (!viewUsage) return <>{children}</>;

  return (
    <Link
      href={viewUsage.href}
      data-testid="balance-view-usage"
      // Dragging across the figures must SELECT them, not pick the link up:
      // these are numbers people copy, and a link is draggable by default.
      draggable={false}
      // The panel's home is a portalled menu, where events bubble along the
      // REACT tree rather than the DOM one. Every control in that menu stops
      // propagation, and this one keeps the habit: whatever the panel is
      // dropped into later must not receive this click as its own.
      onClick={(e) => {
        e.stopPropagation();
        // Let the browser have the gestures that are its: a new tab, a new
        // window, a download. Only the plain click is ours to intercept.
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        // These are figures people copy. A drag that ends inside the link still
        // fires a click, so selecting the balance would otherwise navigate away
        // with the number still highlighted.
        //
        // Scoped three ways, because a link that silently does nothing is worse
        // than one that navigates while text is selected. It bails only when the
        // selection STARTED inside this link (a select-all covers it too, and
        // that reader is not trying to copy the balance), and only for pointer
        // clicks (`detail` is 0 for a keyboard activation, where no drag can
        // have happened).
        const selection = window.getSelection();
        const draggedHere =
          e.detail !== 0 &&
          selection !== null &&
          !selection.isCollapsed &&
          e.currentTarget.contains(selection.anchorNode);
        if (draggedHere) return;
        viewUsage.onNavigate();
      }}
      // Negative margins so the hover surface can breathe without moving the
      // figures a pixel from where they sit in the non-linked version. The
      // horizontal pair cancels the section's own `px-1.5`, which is what puts
      // the rectangle's edges on the menu rows' edges instead of past them.
      className="block -mx-1.5 px-1.5 -my-1 py-1 rounded-xl transition-colors hover:bg-gray-100 dark:hover:bg-gray-800 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)]"
    >
      <span className="sr-only">{actionLabel}</span>
      {children}
    </Link>
  );
}

/**
 * Compact "ring + amount" trigger used inside the sidebar user block, and the
 * fallback wherever no gauge is possible.
 *
 * With `allowance === null` the ring is DROPPED rather than drawn empty: a 0%
 * dial is a statement about the account, and the whole point of a null allowance
 * is that we cannot make one.
 */
export function CreditRingBadge({
  balance,
  gauge,
  hasAllowance,
  compact = false,
}: {
  balance: number | null;
  gauge: CreditGauge;
  hasAllowance: boolean;
  compact?: boolean;
}) {
  const locale = useLocale();
  return (
    <span className="flex items-center gap-1">
      {/* The glyph is suppressed at this size EXCEPT in the gold state, where it
          is a single "+" and is the only non-colour signal separating "40%
          consumed" from "+40% over" on this surface.
          KNOWN TRADE-OFF: this badge pairs a ring filled by credits CONSUMED
          with a number that is credits REMAINING, so a healthy wallet shows a
          nearly-empty ring beside "9.8K". The panel's doc block argues each bar
          should agree with its adjacent number, and this is the one surface
          where it cannot: a 64px rail has no room for a sentence, and
          flipping the ring would put it in contradiction with the dial in the
          menu panel, which is the same widget. That panel resolves it in one
          click and the trigger's aria-label states it outright. */}
      {hasAllowance && (
        <CreditRing
          percent={gauge.fillPct}
          size={compact ? 14 : 16}
          strokeWidth={2}
          gold={gauge.isOver}
          showLabel={gauge.isOver}
        />
      )}
      {/* 10px in the rail: the same size the CE badge this replaces uses in that
          slot (AppSidebar's collapsed user block), because a 12px amount beside a
          14px ring plus a 12px amount is a tight fit in the 64px rail
          (`md:w-16`, less its padding). text-xs everywhere else. */}
      <span className={compact ? 'text-[10px] tabular-nums' : 'text-xs tabular-nums'}>
        {compactCredits(balance, locale)}
      </span>
    </span>
  );
}
