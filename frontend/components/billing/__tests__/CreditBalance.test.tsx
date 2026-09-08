/**
 * @vitest-environment jsdom
 *
 * Render tests for the credit-balance kit: the Balance panel (Total /
 * Remaining / gauge / optional Upgrade CTA) and the ring dial, in both the
 * ordinary "under allowance" state and the gold "over allowance" state.
 *
 * These assert what the maths tests cannot see: that the gold state actually
 * PAINTS gold and states the surplus, that each action appears only where a
 * caller wires it, and that the readout itself is the link to the usage page.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup, fireEvent, createEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';


// The app's locale-aware Link, stubbed the way every other suite stubs it: the
// real one pulls in next/navigation, which does not resolve under vitest. The
// stub records the href it is handed, which is the half this component owns -
// the locale prefix is the Link's own job.
const linkProps = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href, ...rest }: any) => {
    linkProps.last = { href, ...rest };
    return (
      <a href={typeof href === 'string' ? href : '#'} {...rest}>
        {children}
      </a>
    );
  },
}));

import { CreditAllowanceGauge, CreditBalancePanel, CreditRing, CreditRingBadge } from '../CreditBalance';
import { computeCreditGauge } from '@/lib/billing/credit-allowance';

const withIntl = (ui: React.ReactElement) =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      {ui}
    </NextIntlClientProvider>,
  );

/** The bar's fill element. Selected by testid, not by "has an inline width":
 *  the ring's wrapper carries one too (its pixel size). */
const gaugeFill = (container: HTMLElement): HTMLElement | null =>
  container.querySelector<HTMLElement>('[data-testid="balance-gauge-fill"]');

describe('CreditBalancePanel - under allowance', () => {
  afterEach(cleanup);

  it('shows the plan grant as Total and the wallet as Remaining', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );

    expect(screen.getByText('Balance')).toBeTruthy();
    expect(screen.getByText('10,000 credits')).toBeTruthy();
    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779');
  });

  it('fills the gauge by the CONSUMED share, not the remaining one', () => {
    // 9,779 of 10,000 left is 2% used - the bar must be nearly empty, which is
    // the opposite of what filling by "remaining" would draw.
    const { container } = withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(gaugeFill(container)?.style.width).toBe('2%');
  });

  it('renders no over-allowance note while the wallet is within its grant', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(screen.queryByText(/over your plan/i)).toBeNull();
  });

  it('hides Total and the gauge entirely when there is no allowance to gauge against', () => {
    const { container } = withIntl(
      <CreditBalancePanel balance={420} allowance={null} gauge={computeCreditGauge(420, null)} />,
    );
    expect(screen.queryByTestId('balance-total-label')).toBeNull();
    expect(gaugeFill(container)).toBeNull();
    // Remaining still shows: it is a fact about the wallet, not about a plan.
    expect(screen.getByTestId('balance-remaining').textContent).toBe('420');
  });
});

describe('CreditBalancePanel - over allowance (gold)', () => {
  afterEach(cleanup);

  it('states the real surplus percentage', () => {
    withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    expect(screen.getByText('+40% over your plan')).toBeTruthy();
  });

  it('removes the neutral track, so the gold is not laid over a grey bar', () => {
    // The track is a separate span rendered only in the ink state. Leaving it in
    // would put the gold fill on top of the ordinary grey, which is what the
    // "whole bar reads as metal" intent exists to prevent - and the fill
    // assertion below cannot see it.
    const { container: gold } = withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    expect(gold.querySelectorAll('.bg-theme-tertiary')).toHaveLength(0);

    cleanup();
    const { container: ink } = withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );
    expect(ink.querySelectorAll('.bg-theme-tertiary')).toHaveLength(1);
  });

  it('paints the fill gold instead of ink', () => {
    const { container } = withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    const fill = gaugeFill(container)!;
    expect(fill.style.background).toContain('--credit-gold-fill-from');
    // The ink classes must be gone, or the gold would sit under a black bar.
    expect(fill.className).not.toContain('bg-gray-900');
  });

  it('pins the fill at 100% for a huge surplus while still naming the true figure', () => {
    const { container } = withIntl(
      <CreditBalancePanel balance={35_000} allowance={1_000} gauge={computeCreditGauge(35_000, 1_000)} />,
    );
    expect(gaugeFill(container)?.style.width).toBe('100%');
    // Grouped for the app locale: a bare "+3400%" is the number-formatting
    // rule this repo applies to every displayed figure.
    expect(screen.getByText('+3,400% over your plan')).toBeTruthy();
  });
});

describe('CreditBalancePanel - Upgrade CTA', () => {
  afterEach(cleanup);

  it('is absent unless the caller wires it', () => {
    withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );
    expect(screen.queryByTestId('balance-upgrade')).toBeNull();
  });

  it('renders for the menu copy, which does wire it', () => {
    withIntl(
      <CreditBalancePanel
        balance={500}
        allowance={1_000}
        gauge={computeCreditGauge(500, 1_000)}
        onUpgrade={() => {}}
      />,
    );
    expect(screen.getByTestId('balance-upgrade').textContent).toBe('Upgrade');
  });
});

describe('CreditBalancePanel - the readout IS the route to usage', () => {
  afterEach(() => {
    cleanup();
    linkProps.last = null;
  });

  // The panel used to end with a separate "View usage" link. The gauge is what
  // a reader points at when they want to know where the credits went, so the
  // figures and the gauge carry the navigation now and the extra link is gone.

  /** A selection that begins inside `node`, which is what a drag leaves behind. */
  const selectionStartingIn = (node: Node) =>
    vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ isCollapsed: false, anchorNode: node } as unknown as Selection);

  const wired = (over: { onNavigate?: () => void; allowance?: number | null; balance?: number } = {}) => {
    const balance = over.balance ?? 9_779;
    const allowance = over.allowance === undefined ? 10_000 : over.allowance;
    const onNavigate = over.onNavigate ?? vi.fn();
    withIntl(
      <CreditBalancePanel
        balance={balance}
        allowance={allowance}
        gauge={computeCreditGauge(balance, allowance)}
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
      />,
    );
    return { onNavigate, readout: () => screen.getByTestId('balance-view-usage') };
  };

  it('routes client-side when the gauge is clicked', () => {
    const onNavigate = vi.fn();
    wired({ onNavigate });

    const label = screen.getByTestId('balance-gauge-label');
    const click = createEvent.click(label, { bubbles: true });
    fireEvent(label, click);

    expect(onNavigate).toHaveBeenCalledTimes(1);
    // The app owns the plain click, so the browser must not also follow the href.
    expect(click.defaultPrevented).toBe(true);
  });

  it('is a REAL link, so the usage page can be opened in a new tab or copied', () => {
    // This readout is the only route to that page from the cloud user menu, so
    // it carries the URL rather than only a handler.
    const { readout } = wired();
    expect(readout().tagName).toBe('A');
    expect(readout().getAttribute('href')).toBe('/app/settings/quota');
    // Text selection over the figures, rather than picking the link up.
    expect(readout().getAttribute('draggable')).toBe('false');
  });

  it("hands the app's Link the plain path, instead of building the URL itself", () => {
    // An unprefixed /app URL is redirected to a hardcoded /en, so a French
    // reader opening this in a new tab would land on the English page. The fix
    // is to route it through the app's locale-aware Link and let that add the
    // prefix - never to concatenate one here.
    wired();

    expect(linkProps.last?.href).toBe('/app/settings/quota');
  });

  it('routes client-side on a plain pointer click, selection collapsed', () => {
    // The ordinary gesture, distinct from the keyboard path above: a click with
    // detail 1 and nothing selected must reach the app's navigation.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ isCollapsed: true, anchorNode: readout() } as unknown as Selection);

    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 1 }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    selection.mockRestore();
  });

  it.each(['ctrlKey', 'metaKey', 'shiftKey', 'altKey'])(
    'leaves a %s-click to the browser instead of swallowing it',
    (modifier) => {
      // One per flag: cmd is the macOS new-tab gesture, and a handler that drops
      // it while keeping ctrl would ship green on a suite testing only ctrl.
      const onNavigate = vi.fn();
      const { readout } = wired({ onNavigate });

      const click = createEvent.click(readout(), { bubbles: true, [modifier]: true });
      fireEvent(readout(), click);

      expect(onNavigate).not.toHaveBeenCalled();
      expect(click.defaultPrevented).toBe(false);
    },
  );

  it('does not navigate when the reader was selecting the figures to copy', () => {
    // A drag that ends inside the link still fires a click, and these are
    // numbers people copy: highlighting the balance must not route away.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = selectionStartingIn(readout());

    // detail 1 = a pointer click, which is the gesture a drag ends with.
    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 1 }));

    expect(onNavigate).not.toHaveBeenCalled();
    selection.mockRestore();
  });

  it('still activates from the keyboard while a selection is lying around', () => {
    // Keyboard activation fires a click with detail 0. Treating it like a drag
    // would turn a stale selection anywhere on the page into a dead link.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = selectionStartingIn(readout());

    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 0 }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    selection.mockRestore();
  });

  it('still navigates when the selection began elsewhere, a select-all included', () => {
    // A page-wide selection CONTAINS this link, so a containment test would
    // leave that reader with a link that does nothing at all. Only a selection
    // that started inside it is someone copying the balance.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = selectionStartingIn(document.body);

    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 1 }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    selection.mockRestore();
  });

  it('wraps the figures AND the gauge, not just one of them', () => {
    // Clicking the number a reader is looking at has to work too - the whole
    // readout is the target, which is what makes it findable without a link.
    const { readout } = wired();
    expect(readout().contains(screen.getByTestId('balance-remaining'))).toBe(true);
    expect(readout().contains(screen.getByTestId('balance-gauge-fill'))).toBe(true);
  });

  it('leaves the Upgrade CTA OUTSIDE the readout, so one click cannot fire both', () => {
    // The refactor made this newly possible: a CTA moved inside the frame would
    // navigate to pricing AND to usage from a single press.
    const onUpgrade = vi.fn();
    const onNavigate = vi.fn();
    withIntl(
      <CreditBalancePanel
        balance={500}
        allowance={1_000}
        gauge={computeCreditGauge(500, 1_000)}
        onUpgrade={onUpgrade}
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
      />,
    );

    const readout = screen.getByTestId('balance-view-usage');
    const upgrade = screen.getByTestId('balance-upgrade');
    expect(readout.contains(upgrade)).toBe(false);

    upgrade.click();
    expect(onUpgrade).toHaveBeenCalledTimes(1);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('keeps the bucket rows outside it, where the hover surface stops at the rule', () => {
    const onNavigate = vi.fn();
    withIntl(
      <CreditBalancePanel
        balance={1_400}
        allowance={1_000}
        gauge={computeCreditGauge(1_400, 1_000)}
        subBalance={1_000}
        paygBalance={400}
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
      />,
    );

    const readout = screen.getByTestId('balance-view-usage');
    expect(readout.contains(screen.getByTestId('bucket-payg'))).toBe(false);
  });

  it('does not let the click reach whatever the panel is dropped into', () => {
    // Its home is a portalled menu, where React events bubble along the React
    // tree: a container must not receive this click as its own.
    const onAncestorClick = vi.fn();
    withIntl(
      <div onClick={onAncestorClick}>
        <CreditBalancePanel
          balance={500}
          allowance={1_000}
          gauge={computeCreditGauge(500, 1_000)}
          viewUsage={{ href: '/app/settings/quota', onNavigate: () => {} }}
        />
      </div>,
    );

    screen.getByTestId('balance-gauge-label').click();
    expect(onAncestorClick).not.toHaveBeenCalled();
  });

  it('stays a link with no allowance, where there is no gauge to aim at', () => {
    // A guest's wallet has no denominator, so nothing is drawn - the figures
    // still have to lead somewhere.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate, allowance: null, balance: 42_000 });

    expect(screen.queryByTestId('balance-gauge-fill')).toBeNull();
    expect(readout().getAttribute('href')).toBe('/app/settings/quota');
    screen.getByTestId('balance-remaining').click();
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it('names the action for a screen reader without hiding the figures', () => {
    // An aria-label would REPLACE the contents for the accessible name, so the
    // numbers a speech-input user reads on screen would not be in it. The name
    // is built from the contents instead, with the action hidden visually only.
    const { readout } = wired({ balance: 500, allowance: 1_000 });

    const name = readout().getAttribute('aria-label') ?? readout().textContent ?? '';
    expect(name.startsWith('View usage')).toBe(true);
    expect(name).toContain('500');
    expect(screen.getByText('View usage').className).toContain('sr-only');
  });

  it('shows the keyboard where it is, on a surface that matches the rows below', () => {
    // Both were regressions once: no focus ring on a link this menu depends on,
    // and a hover fill louder than the menu rows underneath it.
    const { readout } = wired();
    expect(readout().className).toContain('focus-visible:ring-2');
    expect(readout().className).toContain('focus-visible:ring-offset-1');
    expect(readout().className).toContain('hover:bg-gray-100');
    expect(readout().className).toContain('dark:hover:bg-gray-800');
    // Same corner as the menu rows the rectangle is aligned to.
    expect(readout().className).toContain('rounded-xl');
  });

  it('adds no control at all when the caller wires nowhere to go', () => {
    withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );

    expect(screen.queryByTestId('balance-view-usage')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.queryByText('View usage')).toBeNull();
  });
});

describe('CreditBalancePanel - bucket breakdown', () => {
  afterEach(cleanup);

  it('splits subscription vs top-up only when a top-up exists', () => {
    // Without a top-up the split would just restate Remaining twice.
    withIntl(
      <CreditBalancePanel
        balance={500}
        allowance={1_000}
        gauge={computeCreditGauge(500, 1_000)}
        subBalance={500}
        paygBalance={0}
      />,
    );
    expect(screen.queryByText('PAYG top-up')).toBeNull();
  });

  it('shows both buckets when a top-up is what pushed the wallet over its grant', () => {
    withIntl(
      <CreditBalancePanel
        balance={1_400}
        allowance={1_000}
        gauge={computeCreditGauge(1_400, 1_000)}
        subBalance={1_000}
        paygBalance={400}
      />,
    );
    expect(screen.getByText('Subscription')).toBeTruthy();
    expect(screen.getByText('PAYG top-up')).toBeTruthy();
  });
});

describe('CreditRing', () => {
  afterEach(cleanup);

  it('labels itself with the percentage at header size', () => {
    withIntl(<CreditRing percent={2} />);
    expect(screen.getByText('2%')).toBeTruthy();
  });

  it('drops the label at badge size, where it would be unreadable', () => {
    const { container } = withIntl(<CreditRing percent={2} size={16} />);
    expect(container.textContent).toBe('');
  });

  it('clamps a percentage above 100 so the arc cannot wrap past full', () => {
    const { container } = withIntl(<CreditRing percent={340} />);
    const arc = container.querySelectorAll('circle')[1] as SVGCircleElement;
    const [dash, circumference] = (arc.getAttribute('stroke-dasharray') || '').split(' ').map(Number);
    expect(dash).toBeCloseTo(circumference, 5);
    expect(screen.getByText('100%')).toBeTruthy();
  });

  it('shows a "+" instead of a percentage when the wallet is OVER its grant', () => {
    // An ink "100%" means the wallet is empty; a gold "100%" would mean it holds
    // double the grant. Same four glyphs, opposite meanings, told apart only by
    // hue - so the gold state uses a different glyph, not a different colour.
    withIntl(<CreditRing percent={100} gold />);
    expect(screen.getByTestId('credit-ring-label').textContent).toBe('+');
    expect(screen.queryByText('100%')).toBeNull();
  });

  it('shows a percentage, never a "+", while credits are being consumed', () => {
    withIntl(<CreditRing percent={100} />);
    expect(screen.getByTestId('credit-ring-label').textContent).toBe('100%');
  });

  it('switches the arc stroke to gold in the over-allowance state', () => {
    const { container } = withIntl(<CreditRing percent={40} gold />);
    const arc = container.querySelectorAll('circle')[1] as SVGCircleElement;
    expect(arc.getAttribute('stroke')).toBe('var(--credit-gold-arc)');
    expect(arc.getAttribute('class') ?? '').not.toContain('stroke-black');
  });
});

describe('CreditRingBadge', () => {
  afterEach(cleanup);

  it('renders the compact wallet amount beside the ring', () => {
    withIntl(<CreditRingBadge balance={9_779} gauge={computeCreditGauge(9_779, 10_000)} hasAllowance />);
    expect(screen.getByText('9.8K')).toBeTruthy();
  });

  it('drops a meaningless trailing .0 on a whole sub-1,000 balance', () => {
    // The shared compact formatter always keeps a decimal below 1,000, which
    // put "980.0" next to the ring - noise at badge size.
    withIntl(<CreditRingBadge balance={980} gauge={computeCreditGauge(980, 1_000)} hasAllowance />);
    expect(screen.getByText('980')).toBeTruthy();
  });

  it('keeps a REAL fraction, which is spendable and must not be rounded away', () => {
    withIntl(<CreditRingBadge balance={980.4} gauge={computeCreditGauge(980.4, 1_000)} hasAllowance />);
    expect(screen.getByText('980.4')).toBeTruthy();
  });
});

describe('CreditRingBadge - no denominator', () => {
  afterEach(cleanup);

  it('drops the ring entirely rather than drawing an empty dial', () => {
    // With no allowance a 0% ring would be a statement about the account, and
    // "we cannot compute one" is exactly the opposite statement.
    const { container } = withIntl(
      <CreditRingBadge balance={42_000} gauge={computeCreditGauge(42_000, null)} hasAllowance={false} />,
    );
    expect(container.querySelectorAll('circle')).toHaveLength(0);
    expect(screen.getByText('42.0K')).toBeTruthy();
  });

  it('keeps the ring when there IS an allowance', () => {
    const { container } = withIntl(
      <CreditRingBadge balance={500} gauge={computeCreditGauge(500, 1_000)} hasAllowance />,
    );
    expect(container.querySelectorAll('circle')).toHaveLength(2);
  });
});

describe('CreditBalancePanel - exact amounts', () => {
  afterEach(cleanup);

  it('keeps a spendable fraction on Remaining instead of rounding it up', () => {
    // Rounding half-up would turn 980.6 into "981" and OVERSTATE the balance,
    // and it would disagree with the compact badge that triggers this panel.
    withIntl(
      <CreditBalancePanel balance={980.6} allowance={1_000} gauge={computeCreditGauge(980.6, 1_000)} />,
    );
    expect(screen.getByTestId('balance-remaining').textContent).toBe('980.6');
  });

  it('groups a large amount for the app locale', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779');
  });

  it('hides the header mini-ring too when there is no allowance', () => {
    // The panel and its trigger must agree: if no dial can be drawn, none is.
    const { container } = withIntl(
      <CreditBalancePanel balance={42_000} allowance={null} gauge={computeCreditGauge(42_000, null)} />,
    );
    expect(container.querySelectorAll('circle')).toHaveLength(0);
    expect(screen.queryByTestId('balance-total-label')).toBeNull();
    expect(screen.queryByTestId('balance-gauge-fill')).toBeNull();
  });
});

describe('CreditBalancePanel - the app locale, not the runner default', () => {
  afterEach(cleanup);

  // These components read next-intl's useLocale(), the app locale the provider
  // carries - not the browser's and not the runner's. Asserting only the `en`
  // form would pass on an en-default runner even if the code called a bare
  // toLocaleString(), so each case drives a real provider locale and expects
  // that locale's separators.
  const withLocale = (locale: string, ui: React.ReactElement) =>
    render(
      <NextIntlClientProvider locale={locale} messages={messages as Record<string, unknown>}>
        {ui}
      </NextIntlClientProvider>,
    );

  it('groups Total and Remaining with English separators under en', () => {
    withLocale('en',
      <CreditBalancePanel balance={9_779.4} allowance={10_000} gauge={computeCreditGauge(9_779.4, 10_000)} />);

    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779.4');
    expect(screen.getByText('10,000 credits')).toBeTruthy();
  });

  it('groups them with German separators under de', () => {
    withLocale('de',
      <CreditBalancePanel balance={9_779.4} allowance={10_000} gauge={computeCreditGauge(9_779.4, 10_000)} />);

    expect(screen.getByTestId('balance-remaining').textContent).toBe('9.779,4');
  });

  it('gives the badge the SAME decimal separator as the panel it opens', () => {
    // The shared compact formatter uses toFixed (always a dot), so a German
    // reader used to get "980.4" in the badge and "980,4" one hover away.
    withLocale('de',
      <CreditRingBadge balance={980.4} gauge={computeCreditGauge(980.4, 1_000)} hasAllowance />);

    expect(screen.getByText('980,4')).toBeTruthy();
  });

  it('formats the surplus figure for the locale as well', () => {
    withLocale('de',
      <CreditBalancePanel balance={35_000} allowance={1_000} gauge={computeCreditGauge(35_000, 1_000)} />);

    expect(screen.getByText('+3.400% over your plan')).toBeTruthy();
  });
});

describe('CreditBalancePanel - a surplus too small to state', () => {
  afterEach(cleanup);

  it('says "just over" rather than "+0% over your plan"', () => {
    // 1,001 of a 1,000 grant is genuinely over, but the rounded figure is 0.
    // Printing "+0%" above an empty gold bar reads as a contradiction.
    withIntl(
      <CreditBalancePanel balance={1_001} allowance={1_000} gauge={computeCreditGauge(1_001, 1_000)} />,
    );
    expect(screen.getByText('Just over your plan')).toBeTruthy();
    expect(screen.queryByText(/\+0%/)).toBeNull();
  });
});

describe('CreditBalancePanel - bucket split edge', () => {
  afterEach(cleanup);

  it('stays hidden when the sub bucket is unknown, even with a top-up present', () => {
    // Dropping `subBalance !== null` from the condition would render a row
    // labelled "Subscription" with a dash in it.
    withIntl(
      <CreditBalancePanel
        balance={400}
        allowance={1_000}
        gauge={computeCreditGauge(400, 1_000)}
        subBalance={null}
        paygBalance={400}
      />,
    );
    expect(screen.queryByText('PAYG top-up')).toBeNull();
  });
});

describe('CreditBalancePanel - the two buckets are not interchangeable', () => {
  it('prints the renewal grant and the top-up against the RIGHT labels', () => {
    // Asserting only that the two labels exist let the two VALUES be swapped
    // with the whole suite green. The buckets behave in opposite ways - the
    // subscription one is wiped at the next renewal, the top-up survives it -
    // so showing them the wrong way round inverts the decision a user makes
    // about whether they need to top up.
    withIntl(
      <CreditBalancePanel
        balance={1_400}
        allowance={1_000}
        gauge={computeCreditGauge(1_400, 1_000)}
        subBalance={1_000}
        paygBalance={400}
      />,
    );

    expect(screen.getByTestId('bucket-sub').textContent).toBe('1.0K');
    expect(screen.getByTestId('bucket-payg').textContent).toBe('400');
  });

  it('keeps them apart when the top-up is the larger of the two', () => {
    // Above, the grant is the bigger number; here it is the smaller one, so a
    // swap cannot slip through on a coincidence of magnitude.
    withIntl(
      <CreditBalancePanel
        balance={35_000}
        allowance={1_000}
        gauge={computeCreditGauge(35_000, 1_000)}
        subBalance={1_000}
        paygBalance={34_000}
      />,
    );

    expect(screen.getByTestId('bucket-sub').textContent).toBe('1.0K');
    expect(screen.getByTestId('bucket-payg').textContent).toBe('34.0K');
  });
});

describe('CreditRing - the label has to fit the hole it sits in', () => {
  // Three glyph counts, three sizes: the gold "+" is one glyph and reads
  // largest, "42%" is three, and "100%" is four and has to step down. Collapsing
  // them to one constant changed nothing in any test, while making the emptiest
  // wallet - the state that matters most - the least legible label on the dial.
  const fontPx = (el: HTMLElement) => parseFloat(el.style.fontSize);

  it('gives the gold "+" the largest relative size', () => {
    withIntl(<CreditRing percent={100} size={28} gold showLabel />);
    expect(fontPx(screen.getByTestId('credit-ring-label'))).toBe(14);
  });

  it('uses the standard size for a two-digit percentage', () => {
    withIntl(<CreditRing percent={42} size={28} showLabel />);
    expect(fontPx(screen.getByTestId('credit-ring-label'))).toBe(9);
  });

  it('steps down for "100%", but not all the way to unreadable', () => {
    withIntl(<CreditRing percent={100} size={28} showLabel />);
    const px = fontPx(screen.getByTestId('credit-ring-label'));
    expect(px).toBeLessThan(9);
    expect(px).toBeGreaterThanOrEqual(8);
  });

  it('scales with the ring, so a bigger dial gets bigger type', () => {
    withIntl(<CreditRing percent={42} size={40} showLabel />);
    expect(fontPx(screen.getByTestId('credit-ring-label'))).toBeGreaterThan(9);
  });
});

describe('CreditAllowanceGauge - the bar always says which quantity it shows', () => {
  // The bar fills by credits CONSUMED and sits directly under a row reading
  // "Remaining 9,779", so an unlabelled 2% bar reads as "almost nothing left".
  // The sentence used to render only in the gold state, which left the ordinary
  // case - every healthy account - with a bar and no way to read it. Removing
  // the label again passed 221 tests, so it is pinned here explicitly.

  it('names the consumed share under the plan', () => {
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(9_779, 10_000)} />);
    expect(screen.getByTestId('balance-gauge-label').textContent).toBe('2% of your plan used');
  });

  it('names it at the empty end too', () => {
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(0, 1_000)} />);
    expect(screen.getByTestId('balance-gauge-label').textContent).toBe('100% of your plan used');
  });

  it('switches to the surplus sentence above the plan', () => {
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(1_400, 1_000)} />);
    expect(screen.getByTestId('balance-gauge-label').textContent).toBe('+40% over your plan');
  });

  it('says "just over" rather than "+0% over your plan"', () => {
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(1_001, 1_000)} />);
    expect(screen.getByTestId('balance-gauge-label').textContent).toBe('Just over your plan');
  });

  it('inks the label gold ONLY above the plan', () => {
    // Under the plan the label is ordinary text; painting it gold there would
    // announce a surplus that does not exist.
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(1_400, 1_000)} />);
    expect(screen.getByTestId('balance-gauge-label').style.color).toContain('--credit-gold-ink');

    cleanup();
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(500, 1_000)} />);
    expect(screen.getByTestId('balance-gauge-label').style.color).toBe('');
  });

  it('spells the surplus for the app locale', () => {
    render(
      <NextIntlClientProvider locale="de" messages={messages as Record<string, unknown>}>
        <CreditAllowanceGauge gauge={computeCreditGauge(35_000, 1_000)} />
      </NextIntlClientProvider>,
    );
    expect(screen.getByTestId('balance-gauge-label').textContent).toContain('3.400');
  });
});

describe('CreditRing - the label never goes below a legible floor', () => {
  it('keeps the rail-sized gold "+" at 8px, not 7', () => {
    // 14 x 0.5 rounds to 7px, which is the exact size the sizing note rejects
    // for "100%" as the least legible label on the dial. A "+" is one glyph and
    // has the room, so the floor costs it nothing.
    withIntl(<CreditRing percent={100} size={14} gold showLabel />);
    expect(parseFloat(screen.getByTestId('credit-ring-label').style.fontSize)).toBe(8);
  });

  it('leaves larger rings above the floor untouched', () => {
    // The floor must not flatten the three regimes it sits under.
    withIntl(<CreditRing percent={100} size={28} gold showLabel />);
    expect(parseFloat(screen.getByTestId('credit-ring-label').style.fontSize)).toBe(14);
  });
});

describe('CreditBalancePanel - long labels wrap, numbers do not', () => {
  it('lets the label shrink and wrap rather than truncate', () => {
    // Truncating was worse than wrapping here: the sidebar card is 228px of
    // content, so a German label already overflows at the ordinary tier and
    // would have ellipsised with no title to recover the text from.
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    const label = screen.getByTestId('balance-total-label');
    expect(label.className).toContain('min-w-0');
    expect(label.className).not.toContain('truncate');
  });

  it('keeps both value columns unbreakable, so the label is what gives way', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(screen.getByTestId('balance-remaining').className).toContain('whitespace-nowrap');
    expect(screen.getByText('10,000 credits').className).toContain('whitespace-nowrap');
  });
});

describe('CreditBalancePanel - both rows behave the same under the same pressure', () => {
  // Round 9 replaced `truncate` with wrapping on the grant row, and only that
  // row was pinned - so reintroducing `truncate` on the Remaining label, or
  // dropping `items-start`, passed the whole suite while leaving the two
  // adjacent rows to behave differently, which is how one of them ends up
  // looking broken.
  function labels() {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    return {
      grant: screen.getByTestId('balance-total-label'),
      remaining: screen.getByTestId('balance-remaining-label'),
    };
  }

  it('lets BOTH labels shrink and wrap rather than truncate', () => {
    const { grant, remaining } = labels();
    for (const el of [grant, remaining]) {
      expect(el.className).toContain('min-w-0');
      expect(el.className).not.toContain('truncate');
    }
  });

  it('keeps BOTH value columns unbreakable', () => {
    labels();
    expect(screen.getByTestId('balance-remaining').className).toContain('whitespace-nowrap');
    expect(screen.getByText('10,000 credits').className).toContain('whitespace-nowrap');
  });

  it('aligns both rows to the TOP, so a wrapped label keeps its amount on line one', () => {
    // items-center would drift the amount to the middle of a two-line label,
    // which is the reason wrapping was chosen over truncating in the first place.
    const { grant, remaining } = labels();
    expect(grant.parentElement!.className).toContain('items-start');
    expect(remaining.parentElement!.className).toContain('items-start');
    expect(grant.parentElement!.className).not.toContain('items-baseline');
  });
});

describe('CreditAllowanceGauge - the label carries its own colour', () => {
  it('sets an explicit colour class rather than inheriting one', () => {
    // Both containers that mount this today set `text-theme-primary`, so
    // inheritance looked fine - but the component is exported, and rendered in
    // a container without one it drew dark-on-dark text in dark mode. Nothing
    // in jsdom can see a colour, so the class itself is what gets pinned.
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(9_779, 10_000)} />);
    expect(screen.getByTestId('balance-gauge-label').className).toContain('text-theme-');
  });

  it('lets the gold state override it, since inline colour beats a class', () => {
    withIntl(<CreditAllowanceGauge gauge={computeCreditGauge(1_400, 1_000)} />);
    expect(screen.getByTestId('balance-gauge-label').style.color).toContain('--credit-gold-ink');
  });
});
