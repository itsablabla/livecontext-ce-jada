// @vitest-environment jsdom
/**
 * Install state machine of the marketplace-install store (extracted from
 * AcquirePublicationModal so it survives modal close + page navigation):
 * simulated 5-10s easeOutCubic ramp capped at 95% until the acquire call
 * returns, snap-to-100 + 300ms hold before 'success', endpoint routing per
 * publication type / ceMode, error mapping (402 / 403 CLOUD_ACCOUNT_NOT_LINKED
 * / generic), single-flight guard, and clear() invalidating a pending machine.
 * Timing constants mirror AcquirePublicationModal.stateMachine.test.tsx.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
  createEditableWorkflowCopy: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));
const trackMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: trackMock }));

import { useMarketplaceInstallStore } from '../marketplace-install-store';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Cloud Thing',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    ...overrides,
  } as WorkflowPublication;
}

const store = () => useMarketplaceInstallStore.getState();

describe('marketplace-install store - state machine', () => {
  let nowValue = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    store().clear();
    nowValue = 0;
    vi.useFakeTimers();
    // Deterministic 5000ms target duration (5000 + floor(0 * range)).
    vi.spyOn(Math, 'random').mockReturnValue(0);
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
    svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1', agentId: 'a1', resourceId: 'r1' });
    svc.acquireAgentPublication.mockResolvedValue({ agentId: 'a1' });
    svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'TABLE' });
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' });
    svc.createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', created: true });
  });

  afterEach(() => {
    store().clear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function driveToSuccess() {
    nowValue = 6000; // past the 5000ms target so the interval self-clears at 95%
    await vi.advanceTimersByTimeAsync(6000); // visual-minimum wait + 300ms hold
  }

  it('startInstall enters installing at 0% and tracks app_install_started', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // never resolves
    store().startInstall(pub());

    const active = store().active!;
    expect(active.status).toBe('installing');
    expect(active.progress).toBe(0);
    expect(active.acquiredId).toBeNull();
    expect(trackMock).toHaveBeenCalledWith('app_install_started', expect.objectContaining({
      publication_id: 'pub-1',
      is_free: true,
    }));
  });

  it('eases the progress via easeOutCubic and caps at 95% until the acquire resolves', async () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // call never returns
    store().startInstall(pub());

    // Half the duration: easeOutCubic gives 1-(0.5^3)=0.875 -> 83%, NOT the
    // linear 48% - proves the curve is non-linear.
    nowValue = 2500;
    await vi.advanceTimersByTimeAsync(60);
    expect(Math.round(store().active!.progress)).toBe(83);

    // Past the full duration the bar holds at the 95% cap (the final 5% is
    // reserved for the snap-to-100 once the HTTP call returns).
    nowValue = 6000;
    await vi.advanceTimersByTimeAsync(60);
    expect(store().active!.progress).toBe(95);
    expect(store().active!.status).toBe('installing');
  });

  it('completes to success at 100% with the acquired workflow id after the visual budget', async () => {
    store().startInstall(pub());
    await driveToSuccess();

    const active = store().active!;
    expect(active.status).toBe('success');
    expect(active.progress).toBe(100);
    expect(active.acquiredId).toBe('w1');
    expect(trackMock).toHaveBeenCalledWith('app_install_succeeded', expect.objectContaining({
      publication_id: 'pub-1',
      acquired_id: 'w1',
    }));
  });

  it('routes an AGENT install to the agent endpoint and returns agentId', async () => {
    store().startInstall(pub({ publicationType: 'AGENT' }));
    await driveToSuccess();

    expect(svc.acquireAgentPublication).toHaveBeenCalledWith('pub-1');
    expect(svc.acquirePublication).not.toHaveBeenCalled();
    expect(store().active!.acquiredId).toBe('a1');
  });

  it('routes a resource (INTERFACE) install to the resource endpoint and returns resourceId', async () => {
    svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'INTERFACE' });
    store().startInstall(pub({ publicationType: 'INTERFACE' }));
    await driveToSuccess();

    expect(svc.acquireResourcePublication).toHaveBeenCalledWith('pub-1');
    expect(store().active!.acquiredId).toBe('r1');
  });

  it('ceMode routes EVERY type through the unified remote acquire', async () => {
    store().startInstall(pub({ publicationType: 'TABLE' }), { ceMode: true });
    await driveToSuccess();

    expect(svc.acquireRemotePublication).toHaveBeenCalledWith('pub-1');
    expect(svc.acquireResourcePublication).not.toHaveBeenCalled();
  });

  it('402 → insufficient-credits, 403 CLOUD_ACCOUNT_NOT_LINKED → link-required, generic → error', async () => {
    svc.acquirePublication.mockRejectedValue({ status: 402 });
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('insufficient-credits');

    store().clear();
    svc.acquirePublication.mockRejectedValue({ status: 403, code: 'CLOUD_ACCOUNT_NOT_LINKED' });
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('link-required');

    store().clear();
    svc.acquirePublication.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('error');
    expect(store().active!.error).toBe('boom');
    expect(trackMock).toHaveBeenCalledWith('app_install_failed', expect.objectContaining({ outcome: 'error' }));
  });

  it('403 CE_EXCLUSIVE → ce-exclusive (not the generic error), tracked as its own outcome', async () => {
    // Same status as CLOUD_ACCOUNT_NOT_LINKED, different code: the two must not
    // collapse into one state or the user gets "link your cloud account" for a
    // publication that simply needs a self-hosted install.
    svc.acquirePublication.mockRejectedValue(
      Object.assign(new Error('self-hosted only'), { status: 403, code: 'CE_EXCLUSIVE' }),
    );
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);

    expect(store().active!.status).toBe('ce-exclusive');
    expect(store().active!.error).toBe('self-hosted only');
    expect(trackMock).toHaveBeenCalledWith(
      'app_install_failed',
      expect.objectContaining({ outcome: 'ce_exclusive', error_code: 'CE_EXCLUSIVE' }),
    );
  });

  it('403 PLAN_UPGRADE_REQUIRED → its own state, NOT ce-exclusive', async () => {
    // These two are both 403 on an install and mean opposite things. ce-exclusive renders a dead
    // end with no way forward, which is right for an app that needs a local CLI agent and wrong
    // for one that needs a plan the user can buy in two clicks. Collapsing them would tell a
    // paying customer to go self-host.
    svc.acquirePublication.mockRejectedValue(
      Object.assign(new Error('This app uses vector search, available from the PRO plan.'), {
        status: 403,
        code: 'PLAN_UPGRADE_REQUIRED',
      }),
    );
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);

    expect(store().active!.status).toBe('plan-upgrade-required');
    expect(store().active!.error).toContain('PRO');
    expect(trackMock).toHaveBeenCalledWith(
      'app_install_failed',
      expect.objectContaining({ outcome: 'plan_upgrade_required', error_code: 'PLAN_UPGRADE_REQUIRED' }),
    );
  });

  it('is single-flight: a second startInstall while installing returns false and does nothing', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    expect(store().startInstall(pub())).toBe(true);
    expect(store().startInstall(pub({ id: 'pub-2' }))).toBe(false);

    expect(store().active!.publication.id).toBe('pub-1');
    expect(svc.acquirePublication).toHaveBeenCalledTimes(1);
  });

  it('captures the acquire response resource summary so the post-install screen can name what landed', async () => {
    svc.acquirePublication.mockResolvedValue({
      workflowId: 'w1',
      resources: { interfaces: 2, tables: 1 },
    });
    store().startInstall(pub());
    await driveToSuccess();

    expect(store().active!.resources).toEqual({ interfaces: 2, tables: 1 });
  });

  it('drops zero and non-numeric counts, and tolerates a backend that omits the summary', async () => {
    svc.acquirePublication.mockResolvedValue({
      workflowId: 'w1',
      resources: { interfaces: 1, tables: 0, agents: 'two' },
    });
    store().startInstall(pub());
    await driveToSuccess();
    expect(store().active!.resources).toEqual({ interfaces: 1 });

    store().clear();
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' }); // older backend
    store().startInstall(pub());
    await driveToSuccess();
    expect(store().active!.resources).toEqual({});
  });

  it('records the inline origin flag (marketplace-card rendering vs full-modal consumers)', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    store().startInstall(pub(), { inline: true });
    expect(store().active!.inline).toBe(true);

    store().clear();
    store().startInstall(pub());
    expect(store().active!.inline).toBe(false);
  });

  it('consumeSuccess drops a matching terminal success but never touches a different/running install', async () => {
    // Matching success → consumed.
    store().startInstall(pub());
    await driveToSuccess();
    expect(store().active!.status).toBe('success');
    store().consumeSuccess('pub-1');
    expect(store().active).toBeNull();

    // Running install → consumeSuccess is a strict no-op (the guard is what
    // makes it safe to call from a stale async finally).
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    store().startInstall(pub({ id: 'pub-2' }));
    store().consumeSuccess('pub-2'); // right id but not success
    store().consumeSuccess('pub-1'); // stale id
    expect(store().active!.publication.id).toBe('pub-2');
    expect(store().active!.status).toBe('installing');
  });

  it('a terminal error does NOT block a retry (startInstall runs again)', async () => {
    svc.acquirePublication.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('error');

    store().startInstall(pub());
    expect(store().active!.status).toBe('installing');
    expect(svc.acquirePublication).toHaveBeenCalledTimes(2);
  });

  it('clear() kills a pending machine: a late acquire resolution cannot resurrect state', async () => {
    store().startInstall(pub());
    store().clear();
    expect(store().active).toBeNull();

    await driveToSuccess();
    expect(store().active).toBeNull();
    expect(trackMock).not.toHaveBeenCalledWith('app_install_succeeded', expect.anything());
  });
});

/**
 * Opt-in editable WORKFLOW copy, requested from the acquire modal's checkbox.
 *
 * The copy re-clones the whole snapshot, so it is only made when asked for, and only
 * AFTER the acquire itself succeeded. It is best-effort by design: the application is
 * installed either way, so a copy failure must not turn a working install into an error.
 */
describe('marketplace-install store - opt-in editable copy', () => {
  let nowValue = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    store().clear();
    nowValue = 0;
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0);
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' });
    svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1' });
    svc.createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', created: true });
  });

  afterEach(() => {
    store().clear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function finish() {
    nowValue = 6000;
    await vi.advanceTimersByTimeAsync(6000);
  }

  it('does not touch the copy endpoint when the copy was not requested', async () => {
    store().startInstall(pub());
    await finish();

    expect(svc.createEditableWorkflowCopy).not.toHaveBeenCalled();
    expect(store().active!.status).toBe('success');
    expect(store().active!.editableCopyWorkflowId).toBeNull();
    expect(store().active!.editableCopyFailed).toBe(false);
  });

  it('creates the copy after a successful acquire and reports its workflow id', async () => {
    store().startInstall(pub(), { withEditableCopy: true });
    await finish();

    expect(svc.createEditableWorkflowCopy).toHaveBeenCalledWith('pub-1', false);
    expect(store().active!.status).toBe('success');
    expect(store().active!.editableCopyWorkflowId).toBe('wf-copy');
    expect(store().active!.editableCopyFailed).toBe(false);
  });

  it('never asks for a copy when the acquire itself FAILED (nothing to copy from)', async () => {
    svc.acquirePublication.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));
    store().startInstall(pub(), { withEditableCopy: true });
    await vi.advanceTimersByTimeAsync(10);

    expect(store().active!.status).toBe('error');
    expect(svc.createEditableWorkflowCopy).not.toHaveBeenCalled();
  });

  it('keeps the install SUCCESSFUL when the copy fails, and flags the failure separately', async () => {
    svc.createEditableWorkflowCopy.mockRejectedValue(Object.assign(new Error('quota'), { status: 409 }));
    store().startInstall(pub(), { withEditableCopy: true });
    await finish();

    expect(store().active!.status).toBe('success');
    expect(store().active!.acquiredId).toBe('w1');
    expect(store().active!.editableCopyWorkflowId).toBeNull();
    expect(store().active!.editableCopyFailed).toBe(true);
  });

  it('routes the copy through the remote endpoint in ceMode, like the acquire itself', async () => {
    store().startInstall(pub(), { ceMode: true, withEditableCopy: true });
    await finish();

    expect(svc.acquireRemotePublication).toHaveBeenCalledWith('pub-1');
    expect(svc.createEditableWorkflowCopy).toHaveBeenCalledWith('pub-1', true);
  });

  it('clear() during the copy stops the machine: a late copy cannot resurrect state', async () => {
    let resolveCopy: (v: unknown) => void = () => {};
    svc.createEditableWorkflowCopy.mockReturnValue(new Promise((r) => { resolveCopy = r; }));
    store().startInstall(pub(), { withEditableCopy: true });
    await vi.advanceTimersByTimeAsync(10); // acquire resolved, copy in flight
    store().clear();

    resolveCopy({ workflowId: 'wf-copy', created: true });
    await finish();

    expect(store().active).toBeNull();
    expect(trackMock).not.toHaveBeenCalledWith('app_install_succeeded', expect.anything());
  });
});
