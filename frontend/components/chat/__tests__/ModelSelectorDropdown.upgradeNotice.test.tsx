// @vitest-environment jsdom
/**
 * The composer's model menu is the busiest place a model is chosen, and a chat
 * turn is billed from the pay-as-you-go bucket that a Free plan's monthly
 * credits may not fund. So it carries the same two halves as every picker: the
 * rows are marked, and the way out sits under the list.
 *
 * <p>Both arrive as PROPS. This component is deliberately free of translations
 * and data hooks (see its own header), so the verdict is fetched by the
 * composers that host it and the notice is injected as a node, exactly the way
 * `emptyState` already was.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  // The picker asks once for the credit-estimate basis; these tests are not
  // about the estimate, and an unanswered basis renders no estimate at all.
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('next/image', () => ({ default: () => null }));
// The row reports only whether the verdict reached it; what it draws with that
// is ModelInfo's own suite.
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model, upgradeRequired }: { model: { id: string }, upgradeRequired?: boolean }) => (
    <span data-testid={`row-${model.id}`} data-upgrade={String(!!upgradeRequired)}>{model.id}</span>
  ),
  ModelInfoPopover: () => null,
}));

import { ModelSelectorDropdown } from '../ModelSelectorDropdown';

const models = [
  { id: 'gpt-5-mini', name: 'GPT 5 mini', provider: 'openai', iconSlug: 'openai' },
  { id: 'gpt-5', name: 'GPT 5', provider: 'openai', iconSlug: 'openai' },
] as never[];

function openMenu(props: { upgradeRequired?: boolean, upgradeNotice?: React.ReactNode } = {}) {
  return render(
    <ModelSelectorDropdown
      showModelSelector
      setShowModelSelector={() => {}}
      selectedModel={{ provider: 'openai', id: 'gpt-5-mini' } as never}
      selectedModelData={{ name: 'GPT 5 mini', id: 'gpt-5-mini' }}
      availableModels={models}
      setSelectedModel={() => {}}
      changeModelTitle="change model"
      {...props}
    />,
  );
}

/** Shaped like the real notice: always handed down, renders nothing when clear. */
function UpgradeRequiredNoticeStub({ blocked }: { blocked: boolean }) {
  return blocked ? <a href="/en/app/settings/pricing">see plans</a> : null;
}

let rectSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // The menu renders only once it has measured its trigger, and jsdom measures
  // everything as zero. The sibling placement suite pins a rect for the same
  // reason.
  Object.defineProperty(window, 'innerHeight', { value: 900, writable: true, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: 1400, writable: true, configurable: true });
  const rect = {
    top: 600, bottom: 632, left: 1000, right: 1120, width: 120, height: 32,
    x: 1000, y: 600, toJSON: () => ({}),
  } as DOMRect;
  rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => rect);
});

afterEach(() => {
  rectSpy.mockRestore();
  cleanup();
});

describe('ModelSelectorDropdown - the upgrade warning', () => {
  it('marks every row when this account cannot pay for what a model does', () => {
    openMenu({ upgradeRequired: true });

    const rows = screen.getAllByTestId(/^row-/);
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row).toHaveAttribute('data-upgrade', 'true');
    }
  });

  it('leaves the rows alone by default, which is what an account that can pay sees', () => {
    openMenu();

    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-upgrade', 'false');
    }
  });

  it('regression: puts the way out UNDER the list, never inside a row', () => {
    // A row is an option, and this menu keeps itself open for anything clicked
    // inside it, so a dialog opened from a row would sit underneath the menu it
    // came from. The injected notice is a link, and it lives below the rows.
    openMenu({
      upgradeRequired: true,
      upgradeNotice: <a href="/en/app/settings/pricing">see plans</a>,
    });

    const cta = screen.getByRole('link', { name: 'see plans' });
    expect(cta.closest('[data-testid^="row-"]')).toBeNull();
    const rows = screen.getAllByTestId(/^row-/);
    expect(rows[rows.length - 1].compareDocumentPosition(cta) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
  });

  it('shows nothing extra when the host injects no notice', () => {
    openMenu();

    expect(screen.queryByRole('link')).toBeNull();
  });

  it('regression: leaves no empty strip for a reader who can pay', () => {
    // The hosts hand the notice down unconditionally and it returns null on its
    // own, so keying the wrapper off the NODE rather than the verdict left a
    // rule and a band of dead space at the foot of every menu, for every user
    // on a paid plan, on CE, and on a topped-up Free account alike.
    openMenu({
      upgradeRequired: false,
      upgradeNotice: <UpgradeRequiredNoticeStub blocked={false} />,
    });

    // Queried on the document: the menu is portalled out of the container.
    // The wrapper is what draws the rule and the padding, so its absence is
    // the whole assertion.
    expect(document.querySelector('div.border-t.px-3.py-2')).toBeNull();
  });
});
