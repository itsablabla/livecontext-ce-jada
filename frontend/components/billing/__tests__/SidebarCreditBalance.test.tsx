/**
 * @vitest-environment jsdom
 *
 * The sidebar's two credit pieces: the ring around the avatar, and the section
 * at the top of the user menu.
 *
 * The ring's contract has three parts, and each is a way to break the sidebar
 * rather than merely the feature:
 *
 *  - It WRAPS the avatar, so every path that draws no ring must still render
 *    its children. A `return null` here takes the user's face with it.
 *  - It RESERVES the ring's box instead of overflowing. The first version
 *    pulled itself out with negative offsets, which put it outside the
 *    button's content box: no padding on that button could contain it, and in
 *    the collapsed rail it escaped a 32px button entirely.
 *  - It is NOT a control. It sits inside the user-menu <button>, where a click
 *    target would both navigate and toggle the menu, and a focusable element
 *    would be invalid.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';
import { computeCreditGauge } from '@/lib/billing/credit-allowance';

const mocks = vi.hoisted(() => ({ useCreditWallet: vi.fn(), isCe: { value: false } }));
vi.mock('@/lib/hooks/useCreditWallet', () => ({ useCreditWallet: mocks.useCreditWallet }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return mocks.isCe.value;
  },
}));


// The app's locale-aware Link, stubbed the way every other suite stubs it: the
// real one pulls in next/navigation, which does not resolve under vitest. The
// stub records the href it is handed, which is the half this component owns -
// the locale prefix is the Link's own job.
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href, ...rest }: any) => {
    return (
      <a href={typeof href === 'string' ? href : '#'} {...rest}>
        {children}
      </a>
    );
  },
}));

import { SidebarCreditRing, SidebarCreditMenuSection, CREDIT_RING_BOX } from '../SidebarCreditBalance';

function givenWallet(over: Partial<Record<string, unknown>> = {}) {
  const balance = (over.balance ?? 9_779) as number | null;
  const allowance = (over.allowance === undefined ? 10_000 : over.allowance) as number | null;
  mocks.useCreditWallet.mockReturnValue({
    balance,
    subBalance: null,
    paygBalance: null,
    allowance,
    gauge: computeCreditGauge(balance, allowance),
    isLoading: false,
    ...over,
  });
}

const withIntl = (node: React.ReactNode) =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      {node}
    </NextIntlClientProvider>,
  );

function renderRing() {
  return withIntl(
    <SidebarCreditRing>
      <img data-testid="the-avatar" src="/a.png" alt="Owner" />
    </SidebarCreditRing>,
  );
}

const ring = () => screen.queryByTestId('sidebar-credit-avatar');
const avatar = () => screen.queryByTestId('the-avatar');

beforeEach(() => {
  mocks.isCe.value = false;
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SidebarCreditRing - the avatar survives every no-ring path', () => {
  it('keeps the avatar while the wallet is loading', () => {
    givenWallet({ isLoading: true, balance: null });
    renderRing();
    expect(avatar()).not.toBeNull();
    expect(ring()).toBeNull();
  });

  it('keeps the avatar when the balance is unknown', () => {
    givenWallet({ balance: null });
    renderRing();
    expect(avatar()).not.toBeNull();
    expect(ring()).toBeNull();
  });

  it('keeps the avatar when no denominator is knowable', () => {
    // A guest reading the owner's wallet, or a failed billing read. A ring here
    // would paint "0% used", which is exactly what we do not know.
    givenWallet({ balance: 42_000, allowance: null });
    const { container } = renderRing();
    expect(avatar()).not.toBeNull();
    expect(container.querySelectorAll('circle')).toHaveLength(0);
  });

  it('keeps the avatar in CE, which bills in dollars against no grant', () => {
    mocks.isCe.value = true;
    givenWallet();
    renderRing();
    expect(avatar()).not.toBeNull();
    expect(ring()).toBeNull();
  });

  it('reserves the SAME box on the no-ring paths, so the layout cannot shift', () => {
    // The button around it is sized from this box. If the frame collapsed to
    // the bare avatar whenever the ring is absent, the avatar would jump the
    // moment the wallet resolved.
    givenWallet({ balance: null });
    const { container } = renderRing();
    const frame = container.querySelector('span') as HTMLElement;
    expect(frame.style.width).toBe(`${CREDIT_RING_BOX}px`);
    expect(frame.style.height).toBe(`${CREDIT_RING_BOX}px`);
  });
});

describe('SidebarCreditRing - it reserves its space instead of overflowing', () => {
  it('sizes the wrapper to the avatar PLUS the gap on each side', () => {
    // 32 + 6 + 6 = 44, which is `w-11` on the spacing scale - the reason the
    // gap is 6 and not an arbitrary number. Both buttons are sized from the
    // same total.
    givenWallet();
    renderRing();
    expect(CREDIT_RING_BOX).toBe(44);
    expect(ring()!.style.width).toBe('44px');
    expect(ring()!.style.height).toBe('44px');
  });

  it('fills that box rather than pulling itself out with negative offsets', () => {
    // The regression this replaces: `top: -6px; left: -6px` put the ring
    // outside the button's content box, so no padding could contain it.
    givenWallet();
    renderRing();
    const svg = ring()!.querySelector('svg') as SVGElement;
    expect(svg.getAttribute('class')).toContain('inset-0');
    expect(svg.style.top).toBe('');
    expect(svg.style.left).toBe('');
  });

  it('draws the ring at the reserved size', () => {
    givenWallet();
    renderRing();
    expect(ring()!.querySelector('svg')!.getAttribute('width')).toBe('44');
  });

  it('never intercepts the click that opens the user menu underneath', () => {
    givenWallet();
    renderRing();
    expect(ring()!.querySelector('svg')!.getAttribute('class')).toContain('pointer-events-none');
  });
});

describe('SidebarCreditRing - it is a readout, not a control', () => {
  it('exposes no role and no tab stop', () => {
    givenWallet();
    renderRing();
    expect(ring()!.getAttribute('role')).toBeNull();
    expect(ring()!.hasAttribute('tabindex')).toBe(false);
  });

  it('shows NO number: the ring is the whole indicator on this surface', () => {
    givenWallet();
    renderRing();
    expect(ring()!.textContent).toBe('');
  });

  it('states the consumed share in its accessible label', () => {
    givenWallet({ balance: 9_779, allowance: 10_000 });
    renderRing();
    expect(ring()!.getAttribute('aria-label')).toBe('2% of your plan used');
  });

  it('states the surplus when the wallet is above its grant', () => {
    givenWallet({ balance: 1_400, allowance: 1_000 });
    renderRing();
    expect(ring()!.getAttribute('aria-label')).toBe('+40% over your plan');
  });
});

describe('SidebarCreditRing - the gold state', () => {
  it('strokes the arc with a metallic sweep, not a flat colour', () => {
    // A flat gold cannot work on a white card: any hue bright enough to look
    // like metal falls under the 3:1 a graphical object needs.
    givenWallet({ balance: 1_400, allowance: 1_000 });
    renderRing();

    const arc = ring()!.querySelectorAll('circle')[1];
    expect(arc.getAttribute('stroke')).toMatch(/^url\(#credit-gold-/);

    const stops = [...ring()!.querySelectorAll('stop')].map((n) => n.getAttribute('stop-color'));
    expect(stops).toEqual([
      'var(--credit-gold-metal-1)',
      'var(--credit-gold-metal-2)',
      'var(--credit-gold-metal-3)',
    ]);
  });

  it('defines NO gradient while inside the plan', () => {
    givenWallet({ balance: 500, allowance: 1_000 });
    renderRing();
    expect(ring()!.querySelector('linearGradient')).toBeNull();
  });
});

describe('SidebarCreditMenuSection - the figures, at the top of the user menu', () => {
  const section = () => screen.queryByTestId('sidebar-credit-menu-section');

  function renderSection(handlers: { onNavigate?: () => void; onUpgrade?: () => void } = {}) {
    const onNavigate = handlers.onNavigate ?? vi.fn();
    const onUpgrade = handlers.onUpgrade ?? vi.fn();
    withIntl(
      <SidebarCreditMenuSection
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
        onUpgrade={onUpgrade}
      />,
    );
    return { onNavigate, onUpgrade };
  }

  it('shows the grant, what is left, and the labelled gauge', () => {
    givenWallet({ balance: 9_779, allowance: 10_000 });
    renderSection();

    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779');
    expect(screen.getByText('10,000 credits')).toBeTruthy();
    expect(screen.getByTestId('balance-gauge-fill').style.width).toBe('2%');
    expect(screen.getByTestId('balance-gauge-label').textContent).toBe('2% of your plan used');
  });

  it('carries the Upgrade CTA, since nothing in the top bar does', () => {
    givenWallet();
    const { onUpgrade } = renderSection();
    screen.getByTestId('balance-upgrade').click();
    expect(onUpgrade).toHaveBeenCalledTimes(1);
  });

  it('sends the reader to the usage page from the gauge itself', () => {
    // The menu's own "Credits" row was removed when this arrived, and the
    // panel's separate "View usage" link went with it: the readout is the
    // route now. If it stopped navigating, a cloud account would have no way
    // to that page from this menu at all.
    givenWallet();
    const { onNavigate } = renderSection();
    screen.getByTestId('balance-gauge-label').click();
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it('keeps the readout aligned with the menu rows it sits above', () => {
    // The readout's negative margins are written to cancel THIS wrapper's
    // padding exactly, so the two are one contract: change the padding here
    // and the hover rectangle stops lining up with the rows below.
    givenWallet();
    renderSection();

    expect(section()!.className).toContain('px-1.5');
    const readout = screen.getByTestId('balance-view-usage').className;
    expect(readout).toContain('-mx-1.5');
    expect(readout).toContain('px-1.5');
  });

  it('STILL renders without an allowance, unlike the ring', () => {
    // The ring hides there because it cannot draw a truthful percentage. The
    // panel has no such problem: "Remaining" is still a fact, and this is the
    // only place a guest can read the wallet at all.
    givenWallet({ balance: 42_000, allowance: null });
    renderSection();

    expect(section()).not.toBeNull();
    expect(screen.getByTestId('balance-remaining').textContent).toBe('42,000');
    expect(screen.queryByTestId('balance-gauge-label')).toBeNull();
  });

  it('shows the bucket split when a top-up exists', () => {
    givenWallet({ balance: 1_400, allowance: 1_000, subBalance: 1_000, paygBalance: 400 });
    renderSection();
    expect(screen.getByTestId('bucket-sub').textContent).toBe('1.0K');
    expect(screen.getByTestId('bucket-payg').textContent).toBe('400');
  });

  it('renders nothing in CE', () => {
    mocks.isCe.value = true;
    givenWallet();
    renderSection();
    expect(section()).toBeNull();
  });

  it('renders nothing before the wallet resolves', () => {
    givenWallet({ isLoading: true, balance: null });
    renderSection();
    expect(section()).toBeNull();
  });
});
