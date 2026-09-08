// @vitest-environment jsdom
/**
 * The CE budget field converts between the displayed unit (dollars) and the
 * stored unit (credits, 1 credit = $0.001). A unit slip here is a 1000x error,
 * so pin both directions: seed dollars from stored credits, and convert the
 * typed dollars back to credits on save.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';

// Force the CE edition (dollar display) for this file.
vi.mock('@/lib/edition', () => ({ IS_CE: true, IS_CLOUD: false, EDITION: 'ce' }));
// next-intl: echo the key so labels/placeholders are queryable, no catalog needed.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import { EditMetadataModal } from '../EditMetadataModal';

function numberInput(): HTMLInputElement {
  const el = document.querySelector('input[type="number"]');
  if (!el) throw new Error('budget input not found');
  return el as HTMLInputElement;
}

describe('EditMetadataModal - CE budget conversion', () => {
  it('seeds the field in dollars from stored credits (1000 credits -> $1)', () => {
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(numberInput().value).toBe('1');
  });

  it('seeds a fractional dollar cleanly (350 credits -> "0.35", no float artifact)', () => {
    // Regression for the IEEE-754 artifact: the pre-fix `credits * 0.001` seeded
    // this as "0.35000000000000003". The integer-factor conversion must not.
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={350}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(numberInput().value).toBe('0.35');
  });

  it('converts typed dollars back to credits on save ($2 -> 2000 credits)', () => {
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.change(numberInput(), { target: { value: '2' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave).toHaveBeenCalledTimes(1);
    const arg = onSave.mock.calls[0][0];
    expect(arg.budgetCredits).toBeCloseTo(2000, 3);
  });

  it('a blank budget clears it (null credits)', () => {
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.change(numberInput(), { target: { value: '' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave.mock.calls[0][0].budgetCredits).toBeNull();
  });

  it('does not render the budget field for non-workflow resources', () => {
    render(
      <EditMetadataModal
        resourceType="datasource"
        initialName="Ds"
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(document.querySelector('input[type="number"]')).toBeNull();
  });
});

describe('EditMetadataModal - budget period cadence', () => {
  function periodSelect(): HTMLSelectElement | null {
    return document.querySelector('select');
  }

  it('re-seeds the cadence when it arrives after the modal opened', async () => {
    // The opener seeds the metadata synchronously and fills the budget from an
    // async fetch, so the modal is constructed with a null cadence. Reading the
    // prop once would leave the select on its 'monthly' default, and since the
    // field is always submitted, a plain rename would silently turn a workflow
    // the user set to "never resets" into a monthly one.
    const { rerender } = render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode={null}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(periodSelect()?.value).toBe('monthly');

    rerender(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode="cumulative"
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(periodSelect()?.value).toBe('cumulative');
  });

  it('saves the cadence that arrived late, not the default it was built with', () => {
    const onSave = vi.fn();
    const { rerender } = render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    rerender(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode="weekly"
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    const save = screen.getByText('save');
    fireEvent.click(save);

    expect(onSave).toHaveBeenCalled();
    expect(onSave.mock.calls[0][0].budgetPeriodMode).toBe('weekly');
  });

  it('hides the cadence entirely while no cap is typed', () => {
    // With no cap there is nothing to reset, and an empty field must stay the
    // zero-friction default rather than forcing a choice.
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={null}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    expect(periodSelect()).toBeNull();
  });

  it('omits the cadence from a save made BEFORE the real one has arrived', () => {
    // The other half of the same defect, and the half the re-seed cannot cover.
    // The opener has to fetch the cadence, so a user who renames and saves fast
    // submits whatever the select is showing, which is the component's own
    // 'monthly' default. Sending it would silently rewrite a "never resets"
    // workflow to monthly on a save that had nothing to do with the budget.
    // Leaving the field out means the server keeps what it has.
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByText('save'));

    expect(onSave).toHaveBeenCalled();
    expect(onSave.mock.calls[0][0].budgetPeriodMode).toBeUndefined();
  });

  it('sends the cadence the USER picked, even when none ever arrived from the server', () => {
    // Choosing a value is knowing it. Suppressing an explicit pick would make
    // the control silently inert for a workflow whose fetch failed.
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    const select = periodSelect();
    expect(select).not.toBeNull();
    fireEvent.change(select!, { target: { value: 'cumulative' } });
    fireEvent.click(screen.getByText('save'));

    expect(onSave.mock.calls[0][0].budgetPeriodMode).toBe('cumulative');
  });

  it('omits the cap AMOUNT from a save made before the stored value has arrived', () => {
    // The expensive half. An empty amount field means "no cap", so a rename
    // saved during the fetch does not merely skip an update: it CLEARS a
    // spending cap the user never touched, silently. The cadence had a guard
    // for exactly this; the amount beside it did not.
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByText('save'));

    expect(onSave.mock.calls[0][0].budgetCredits).toBeUndefined();
  });

  it('still CLEARS a cap the user can actually see, which is a deliberate act', () => {
    // The guard must not make clearing impossible: once a stored cap has
    // arrived the field shows it, so emptying it is a choice, not a gap.
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    fireEvent.change(numberInput(), { target: { value: '' } });
    fireEvent.click(screen.getByText('save'));

    expect(onSave.mock.calls[0][0].budgetCredits).toBeNull();
  });

  it('sends a cap the user typed even though none was ever stored', () => {
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByText('advancedSection'));
    fireEvent.change(numberInput(), { target: { value: '3' } });
    fireEvent.click(screen.getByText('save'));

    expect(onSave.mock.calls[0][0].budgetCredits).toBe(3000);
  });

  it('a late arrival never overwrites an amount the user is typing', () => {
    // The same clobber as the cadence one, in the other direction: the fetch
    // resolves while the user is mid-edit and replaces what they wrote.
    const onSave = vi.fn();
    const { rerender } = render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.click(screen.getByText('advancedSection'));
    fireEvent.change(numberInput(), { target: { value: '7' } });

    rerender(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    expect(numberInput().value).toBe('7');
  });

  it('a late arrival never overwrites a cadence the user has already picked', () => {
    const onSave = vi.fn();
    const { rerender } = render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode={null}
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.change(periodSelect()!, { target: { value: 'cumulative' } });

    rerender(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode="weekly"
        onClose={() => {}}
        onSave={onSave}
      />,
    );
    fireEvent.click(screen.getByText('save'));

    expect(onSave.mock.calls[0][0].budgetPeriodMode).toBe('cumulative');
  });

  it('shows "leave empty for no cap" while the field IS empty, which is when it answers something', () => {
    // It used to live inside the cadence block, which only renders once an
    // amount is typed, so the one sentence explaining an empty field was
    // invisible for exactly as long as the field was empty.
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={null}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );
    fireEvent.click(screen.getByText('advancedSection'));
    expect(screen.getByText('capHelp')).toBeTruthy();
    // And the cadence select is still hidden: with no cap there is nothing to
    // reset, so the help is reachable without forcing a cadence choice.
    expect(periodSelect()).toBeNull();
  });
});

describe('EditMetadataModal - a CLOSED fold contributes nothing', () => {
  /**
   * The rule the create form follows, applied here too. Without it this form had
   * two silent failures, and the second is the worse one:
   *
   *  - open Advanced, type a cap, collapse, Save -> the cap was APPLIED from a
   *    field the user could no longer see;
   *  - clear a stored cap, collapse, Save -> the cap was REMOVED, which is the
   *    silent uncapping the `capKnown` machinery exists to prevent, arriving by
   *    a route it does not cover.
   *
   * Both are verified below by the ABSENCE of the key from the payload, which is
   * what "leave the cap alone" means on this endpoint: the backend patches only
   * the fields it is sent.
   */
  const toggle = () => screen.getByTestId('budget-advanced-toggle');
  const save = () => fireEvent.click(screen.getByRole('button', { name: 'save' }));

  it('does not apply a cap typed into a fold that was then closed', () => {
    const onSave = vi.fn();
    render(
      <EditMetadataModal resourceType="workflow" initialName="Wf" onClose={() => {}} onSave={onSave} />,
    );

    fireEvent.click(toggle());
    fireEvent.change(numberInput(), { target: { value: '5' } });
    fireEvent.click(toggle());
    save();

    expect(onSave).toHaveBeenCalled();
    expect(onSave.mock.calls[0][0].budgetCredits, 'a cap was applied from a hidden field').toBeUndefined();
  });

  it('does not REMOVE a stored cap from a fold that was closed', () => {
    // The destructive one. `budgetCredits: null` on this endpoint uncaps the
    // workflow, so "undefined" is the only safe answer for an invisible field.
    const onSave = vi.fn();
    render(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        initialBudgetPeriodMode="weekly"
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    fireEvent.change(numberInput(), { target: { value: '' } });
    fireEvent.click(toggle());
    save();

    expect(onSave.mock.calls[0][0].budgetCredits, 'the cap was cleared from a hidden field').toBeUndefined();
    expect(onSave.mock.calls[0][0].budgetPeriodMode).toBeUndefined();
  });

  // A change-detector: fails on the revision before this guard existed.
  it('does not let a late-arriving cap re-open a fold the user closed', () => {
    // The route that defeated the rule. The opener often has to FETCH the cap,
    // so: open Advanced before it lands, type something, collapse to mean
    // "forget that" - and the arriving value used to re-open the fold, still
    // holding the typed amount, which Save then wrote over the stored cap.
    const onSave = vi.fn();
    const { rerender } = render(
      <EditMetadataModal resourceType="workflow" initialName="Wf" onClose={() => {}} onSave={onSave} />,
    );

    fireEvent.click(toggle());
    fireEvent.change(numberInput(), { target: { value: '5' } });
    fireEvent.click(toggle());

    // The fetch lands.
    rerender(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={onSave}
      />,
    );

    expect(
      screen.queryByTestId('budget-advanced-fields'),
      'a late arrival re-opened a fold the user had closed'
    ).toBeNull();

    save();
    expect(
      onSave.mock.calls[0][0].budgetCredits,
      'the hidden amount was written over the stored cap'
    ).toBeUndefined();
  });

  it('still opens the fold for a cap that arrives before the user touches anything', () => {
    // NOT a change-detector: this behaviour predates the guard. It is here as
    // the counterweight - the thing the fix must not break - so a future
    // tightening of the guard cannot quietly stop showing a stored cap.
    // The reason the effect exists at all: a cap has to be visible, or the form
    // looks like the workflow has none.
    const { rerender } = render(
      <EditMetadataModal resourceType="workflow" initialName="Wf" onClose={() => {}} onSave={() => {}} />,
    );
    expect(screen.queryByTestId('budget-advanced-fields')).toBeNull();

    rerender(
      <EditMetadataModal
        resourceType="workflow"
        initialName="Wf"
        initialBudgetCredits={1000}
        onClose={() => {}}
        onSave={() => {}}
      />,
    );

    expect(screen.getByTestId('budget-advanced-fields')).toBeTruthy();
    expect(numberInput().value).toBe('1');
  });

  it('still saves a cap the user can actually see', () => {
    // Also a counterweight rather than a detector: closing the fold is what
    // withholds the value, not the form quietly refusing to save caps.
    const onSave = vi.fn();
    render(
      <EditMetadataModal resourceType="workflow" initialName="Wf" onClose={() => {}} onSave={onSave} />,
    );

    fireEvent.click(toggle());
    fireEvent.change(numberInput(), { target: { value: '5' } });
    save();

    // CE: $5 -> 5000 credits.
    expect(onSave.mock.calls[0][0].budgetCredits).toBe(5000);
  });

});
