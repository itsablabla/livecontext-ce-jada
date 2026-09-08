/**
 * @vitest-environment jsdom
 *
 * That clicking a past fire on the calendar actually lands on THAT fire.
 *
 * The agenda navigates to a run, and a run's surfaces open on the cumulative view of every
 * fire the run ever had. That default is right when you open a run and wrong when you
 * clicked one dot on a calendar: the user pointed at Tuesday 09:00 and got every Tuesday at
 * once. The handoff is a module-scope memory keyed by run id, written before the navigation
 * and read by the run panel when it mounts.
 *
 * Two ends and no middle is exactly how a handoff ships broken: the agenda writes, the panel
 * reads, and nothing renders both. So this test drives the real memory and the real hook,
 * rather than asserting that a function was called.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import {
  markEpochPickedByUser,
  getPickedEpoch,
  resetEpochSelectionState,
  useDefaultEpochSelection,
} from '@/components/workflow/run-panel/useDefaultEpochSelection';

const RUN = 'run_agenda_1';

describe('agenda -> run epoch handoff', () => {
  beforeEach(() => {
    resetEpochSelectionState();
  });

  it('a run panel mounting after the calendar wrote a pick opens ON that epoch', () => {
    // The whole path: the agenda records the fire it is navigating to, then the panel
    // mounts with nothing selected and adopts it.
    markEpochPickedByUser(RUN, 7);
    const onSelectEpoch = vi.fn();

    renderHook(() =>
      useDefaultEpochSelection({ runId: RUN, selectedEpoch: null, onSelectEpoch }),
    );

    expect(onSelectEpoch).toHaveBeenCalledWith(7);
  });

  it('restores once the panel has ADOPTED the run, not before', () => {
    // The panel gates this on having bound the run (`enabled`). A restore fired while
    // disabled would broadcast an epoch against the wrong run - the cross-talk the panel's
    // scoping exists to prevent.
    markEpochPickedByUser(RUN, 7);
    const onSelectEpoch = vi.fn();

    const { rerender } = renderHook(
      ({ enabled }) =>
        useDefaultEpochSelection({ runId: RUN, selectedEpoch: null, onSelectEpoch, enabled }),
      { initialProps: { enabled: false } },
    );
    expect(onSelectEpoch).not.toHaveBeenCalled();

    rerender({ enabled: true });
    expect(onSelectEpoch).toHaveBeenCalledWith(7);
  });

  it('leaves another run alone', () => {
    // The memory is keyed by run id, so a calendar click on one workflow cannot pull a
    // different run onto an epoch it never had.
    markEpochPickedByUser(RUN, 7);
    const onSelectEpoch = vi.fn();

    renderHook(() =>
      useDefaultEpochSelection({ runId: 'run_other', selectedEpoch: null, onSelectEpoch }),
    );

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('does not override an epoch the user is already looking at', () => {
    // Opening a run the calendar wrote a pick for, then paging to another epoch by hand,
    // must not snap back.
    markEpochPickedByUser(RUN, 7);
    const onSelectEpoch = vi.fn();

    renderHook(() =>
      useDefaultEpochSelection({ runId: RUN, selectedEpoch: 3, onSelectEpoch }),
    );

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('writes nothing for a projection, which has no epoch', () => {
    // The agenda only records a pick for a PAST occurrence. A planned one carries no
    // epoch, and inventing one would open the run on a fire that has not happened.
    expect(getPickedEpoch(RUN)).toBeUndefined();

    const onSelectEpoch = vi.fn();
    renderHook(() =>
      useDefaultEpochSelection({ runId: RUN, selectedEpoch: null, onSelectEpoch }),
    );

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });
});
