/**
 * @vitest-environment jsdom
 *
 * An application opened in the right side panel is a full application surface.
 *
 * It used to render ONE interface with no sub-tabs: no way to watch the workflow
 * run, no run history, no other page of a multi-page app, and the navigate link
 * between pages had nowhere to go (see WorkflowPanelContent.navigateAction).
 * It now hands the resolved publication to the same composition the sub-workflow
 * tab uses, with the application as its opening tab.
 *
 * This file pins the hand-off, not the panel it hands to: what the resolver
 * produces (every interface, the run it resolved, the template actions) and the
 * flags that make the Application the tab it opens on.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

/** Props the composition was mounted with. */
const mountedWith = vi.hoisted(() => ({ current: null as Record<string, any> | null }));
/** Marketplace-preview context, off by default. */
const previewCtx = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));
const snapshotCtx = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getPublicationById: vi.fn(),
    getAcquiredApplications: vi.fn(),
    getPublicationByIdPublic: vi.fn(),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getApplicationRun: vi.fn().mockResolvedValue({ runId: 'run_abc' }),
    executeWorkflow: vi.fn(),
  },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn().mockResolvedValue({
      plan: {
        interfaces: [
          { id: 'iface-1', label: 'Home', actionMapping: { go: 'x' } },
          { id: 'iface-2', label: 'Details Page', isEntryInterface: true, actionMapping: {} },
        ],
      },
    }),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => previewCtx.current,
  usePublicationSnapshot: () => snapshotCtx.current,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (s: string) => s.toLowerCase().replace(/\s+/g, '_'),
}));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({
  WorkflowBuilderPanelContent: (props: Record<string, any>) => {
    mountedWith.current = props;
    return <div data-testid="workflow-panel" />;
  },
}));

import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { ApplicationPanelContent } from '../ApplicationSidePanel';

/** A publication of someone else's, which the caller HAS installed. */
const NOT_MINE = {
  id: 'pub-1', workflowId: 'wf-1', title: 'App', showcaseInterfaceId: 'iface-1', ownedByMe: false,
};
/** The same publication, published BY the caller. */
const MINE = { ...NOT_MINE, ownedByMe: true };
/** The install: an APPLICATION clone of the source, linked to the publication. */
const INSTALLED = { applications: [{ sourcePublicationId: 'pub-1', workflowId: 'wf-clone' }] };
const NOT_INSTALLED = { applications: [] };

/**
 * Every fixture is re-declared per test rather than queued with
 * `mockResolvedValueOnce`: a queued value survives a test that never consumes it,
 * so a reordered or inserted test would silently inherit another one's
 * publication - the difference between "mine" and "not mine" being exactly what
 * these tests are about.
 */
function givenPublication(
  pub: Record<string, unknown>,
  acquired: { applications: Array<Record<string, unknown>> },
) {
  vi.mocked(publicationService.getPublicationById).mockResolvedValue(pub as never);
  vi.mocked(publicationService.getAcquiredApplications).mockResolvedValue(acquired as never);
}

describe('ApplicationSidePanel - composes the shared workflow panel', () => {
  beforeEach(() => {
    mountedWith.current = null;
    previewCtx.current = null;
    snapshotCtx.current = null;
    vi.mocked(publicationService.getPublicationByIdPublic).mockResolvedValue({
      // The preview path reads the sanitized public record, never the tenant one.
      // `ownedByMe` is deliberately TRUE here: the publisher previewing their own
      // card must be as locked as an anonymous visitor, and without it the preview
      // term of the gate would pass for the wrong reason.
      id: 'pub-1', workflowId: 'wf-1', title: 'App', showcaseInterfaceId: 'iface-1',
      ownedByMe: true,
      showcaseRunId: 'run-showcase',
      planSnapshot: { interfaces: [{ id: 'iface-1', label: 'Frozen', actionMapping: {} }] },
    } as never);
    givenPublication(NOT_MINE, INSTALLED);
  });
  afterEach(cleanup);

  it('opens on the application, on the run it resolved, for the acquired clone', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.workflowId).toBe('wf-clone');
    expect(mountedWith.current!.runId).toBe('run_abc');
    // Without this the panel opens on the canvas: the user asked for the app.
    expect(mountedWith.current!.applicationFirst).toBe(true);
    expect(mountedWith.current!.readOnly).toBe(false);
    // THE reachable defect: an ordinary acquirer. An installed application is a
    // FROZEN clone (409 ApplicationPlanImmutableException on every plan write), and
    // the panel used to hand out the edit toggle and a Save that could only fail.
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
    // The ordinary acquirer, and the case that pins the INSTALL half of the
    // related-workflow answer: what they installed brought sub-workflows into
    // their own tenant as writable clones, so those must stay editable. Reading
    // ownership alone here would silently make an acquirer's sub-workflows
    // read-only.
    expect(mountedWith.current!.canEditRelatedWorkflows).toBe(true);
  });

  it('locks an install even where ownership of the publication would say otherwise', async () => {
    // The install always wins. The backend refuses to acquire your own
    // publication, so this exact pair is not a state the product mints today -
    // it is here because the gate must be decided by the install and never by
    // ownership, which is what the pre-change code got wrong.
    givenPublication(MINE, INSTALLED);

    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.workflowId, 'the installed clone, not the source').toBe('wf-clone');
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
    // The application's own plan is frozen, but the sub-workflows it calls were
    // cloned as ordinary workflows in this tenant: the backend writes those, so
    // locking them would deny an edit that works.
    expect(mountedWith.current!.canEditRelatedWorkflows).toBe(true);
    // ...and the reset now reaches the clone whose tables are the ones on screen.
    // Keyed on ownership it was withheld from exactly this user.
    expect(mountedWith.current!.applicationTemplateSource).toEqual({
      publicationId: 'pub-1', remote: false, canReset: true,
    });
  });

  it('keeps the workflow editable for the publisher of an app they have NOT installed', async () => {
    // No clone exists, so the panel binds the editable SOURCE workflow - the one
    // "Publish update" snapshots. This is the single case that may be saved, and
    // the case the lock above must not swallow. (A control: it passed before the
    // lock too, and its job is to prove the lock did not swallow it.)
    givenPublication(MINE, NOT_INSTALLED);

    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.workflowId).toBe('wf-1');
    expect(mountedWith.current!.canEditWorkflow).toBe(true);
    expect(mountedWith.current!.canEditRelatedWorkflows).toBe(true);
    // No clone to wipe: offering the reset here would target nothing.
    expect(mountedWith.current!.applicationTemplateSource).toEqual({
      publicationId: 'pub-1', remote: false, canReset: false,
    });
  });

  it('locks the workflow of an installed application opened on a live run', async () => {
    // The runId-override path (an agent's execute marker) resolves the clone
    // through its own branch, which carried the same "installed means mine to
    // edit" mistake.
    givenPublication(MINE, INSTALLED);

    render(<ApplicationPanelContent publicationId="pub-1" runId="run_live" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.runId).toBe('run_live');
    expect(mountedWith.current!.workflowId).toBe('wf-clone');
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
  });

  it('keeps the publisher editing when the install lookup FAILS, because it stays on the source', async () => {
    // The lookup is the only thing that knows about a clone, so its failure has
    // to fail in the direction that stays true: no clone was bound, the panel is
    // still on the publisher's own source workflow, and that one really is
    // writable. Locking on a transport error would strand the publisher instead.
    vi.mocked(publicationService.getPublicationById).mockResolvedValue(MINE as never);
    vi.mocked(publicationService.getAcquiredApplications).mockRejectedValue(new Error('offline'));

    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.workflowId).toBe('wf-1');
    expect(mountedWith.current!.canEditWorkflow).toBe(true);
  });

  it('offers no edit toggle and no actions when the publication resolves to someone else workflow', async () => {
    givenPublication(NOT_MINE, NOT_INSTALLED);
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    // Falls back to the publisher's workflow: readable, but every save, run and
    // publish there would be refused.
    expect(mountedWith.current!.workflowId).toBe('wf-1');
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
    // Nothing reachable from here is ours either: a sub-workflow node opened from
    // this canvas must not hand out a Save on the publisher's graph.
    expect(mountedWith.current!.canEditRelatedWorkflows).toBe(false);
    // Nothing was installed, so the reset has no clone to resolve and the
    // endpoint would answer 404. Keyed on ownership it was offered here.
    expect(mountedWith.current!.applicationTemplateSource).toEqual({
      publicationId: 'pub-1', remote: false, canReset: false,
    });
  });

  it('binds the publisher workflow when the install lookup fails on an app the caller DID install', async () => {
    // The mirror of the owner case: the lookup is the only thing that knows about
    // the clone, so its failure leaves the panel on someone else's workflow. The
    // edit gate must still refuse (it is not the caller's), and the reset must
    // not be offered against an install this render never resolved.
    vi.mocked(publicationService.getPublicationById).mockResolvedValue(NOT_MINE as never);
    vi.mocked(publicationService.getAcquiredApplications).mockRejectedValue(new Error('offline'));

    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.workflowId).toBe('wf-1');
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
    expect(mountedWith.current!.applicationTemplateSource).toEqual({
      publicationId: 'pub-1', remote: false, canReset: false,
    });
  });

  it('seeds EVERY page of the application, flagging the declared entry', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    // The single-interface panel could not show a second page at all.
    expect(mountedWith.current!.initialApplicationConfigs).toEqual([
      { interfaceId: 'iface-1', label: 'Home', actionMapping: { go: 'x' }, nodeId: 'interface:home', isEntryInterface: false },
      { interfaceId: 'iface-2', label: 'Details Page', actionMapping: {}, nodeId: 'interface:details_page', isEntryInterface: true },
    ]);
  });

  it('keeps the template actions of an installed application', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.applicationTemplateSource).toEqual({
      publicationId: 'pub-1', remote: false, canReset: true,
    });
    // Live surface: the canvas reads the tenant's own plan, never a frozen one.
    expect(mountedWith.current!.planOverride).toBeUndefined();
  });

  it('renders a preview read-only, off the frozen snapshot rather than the tenant plan', async () => {
    previewCtx.current = { remote: false };
    snapshotCtx.current = {
      planSnapshot: { interfaces: [{ id: 'iface-1', label: 'Frozen', actionMapping: {} }] },
    };

    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.readOnly, 'someone else frozen showcase').toBe(true);
    // The preview fixture is owned BY the caller and has no clone, which is the
    // one shape that would otherwise satisfy both gates: it is the preview term,
    // and nothing else, that has to refuse them here.
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
    expect(mountedWith.current!.canEditRelatedWorkflows).toBe(false);
    // The canvas must render the snapshot, never the tenant's live plan: a
    // preview visitor has no access to it.
    expect(mountedWith.current!.planOverride).toEqual({
      interfaces: [{ id: 'iface-1', label: 'Frozen', actionMapping: {} }],
    });
    // No template actions on a showcase clone.
    expect(mountedWith.current!.applicationTemplateSource).toBeUndefined();
  });
});
