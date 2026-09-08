// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Two properties of the Memory tab that are privacy-relevant rather than
 * cosmetic, so they are pinned rather than left to review:
 *
 * 1. The list is cached per workspace. If the query key did not carry the active
 *    workspace, switching workspace would show the previous workspace's memories
 *    until a refetch landed. For a list of facts about a team and its people,
 *    that is a leak, not a stale-data annoyance.
 * 2. A VIEWER gets no write affordance at all. The backend refuses the write
 *    either way, but a button that always fails is worse than no button.
 */

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  search: vi.fn(),
  get: vi.fn(),
  currentOrgId: 'org-alpha' as string | null,
  canMutate: true,
  capturedQueryKeys: [] as unknown[][],
  invalidateQueries: vi.fn(),
  refetch: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

vi.mock('@/lib/api/orchestrator/memory.service', () => ({
  memoryService: {
    list: mocks.list,
    search: mocks.search,
    get: mocks.get,
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}));

// The REAL useOrgScopedQuery is used: stubbing it would have meant asserting the
// key the stub built, which proves the component calls the hook and nothing about
// how the hook keys. Only react-query's useQuery underneath is stubbed, so the
// effective key captured below is the one useOrgScopedQuery actually produces.

vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => mocks.canMutate,
  // The tab reads the active workspace directly to scope its cache invalidation
  // to its own list, so the mock has to expose the selector form the real store has.
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null }) => unknown) =>
    selector({ currentOrgId: mocks.currentOrgId }),
}));

vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: mocks.invalidateQueries }),
  useQuery: (options: { queryKey: unknown[]; queryFn: () => Promise<unknown>; enabled?: boolean }) => {
    mocks.capturedQueryKeys.push(options.queryKey);
    const [data, setData] = React.useState<unknown>(undefined);
    // A rejection has to reach the component as isError. The stub used to
    // swallow it and leave data undefined, which models "still loading forever"
    // and made every error-path assertion unwritable.
    const [isError, setIsError] = React.useState(false);
    const enabled = options.enabled !== false;
    React.useEffect(() => {
      if (!enabled) return;
      let cancelled = false;
      setIsError(false);
      options.queryFn().then(
        (d) => { if (!cancelled) setData(d); },
        () => { if (!cancelled) setIsError(true); },
      );
      return () => { cancelled = true; };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [JSON.stringify(options.queryKey), enabled]);
    return {
      data,
      isError,
      // In flight means: enabled, and neither an answer nor an error yet. The
      // component distinguishes "no matches" from "no answer yet", so the stub
      // has to be able to sit in the second state.
      isFetching: enabled && data === undefined && !isError,
      isLoading: enabled && data === undefined && !isError,
      refetch: mocks.refetch,
    };
  },
}));

vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div>loading</div> }));
vi.mock('@/components/ui/EmptyState', () => ({
  EmptyState: ({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: React.ReactNode }) => (
    <div data-testid="empty"><span>{title}</span><span>{subtitle}</span>{actions}</div>
  ),
}));
vi.mock('@/components/Toast', () => ({
  __esModule: true,
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/memory/MemoryEditorModal', () => ({
  MemoryEditorModal: ({ memory }: { memory: { content?: string } | null }) => (
    <div data-testid="editor">{memory?.content ?? ''}</div>
  ),
}));
vi.mock('@/lib/utils/dateFormatters', () => ({ formatUtcDate: () => '2026-09-06' }));

import { MemoryTab } from '../MemoryTab';

const memory = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 'm1',
  slug: 'release-cadence',
  title: 'Release cadence',
  summary: 'The team ships on Thursdays.',
  content: '',
  type: 'project',
  tags: [],
  pinned: false,
  source: 'agent',
  agentId: null,
  scope: 'workspace',
  isActive: true,
  recallCount: 3,
  lastRecalledAt: null,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-05T00:00:00Z',
  ...over,
});

describe('MemoryTab - workspace scoping and read-only role', () => {
  beforeEach(() => {
    mocks.capturedQueryKeys.length = 0;
    mocks.currentOrgId = 'org-alpha';
    mocks.canMutate = true;
    mocks.list.mockReset().mockResolvedValue([memory()]);
    mocks.search.mockReset().mockResolvedValue([]);
  });

  afterEach(cleanup);

  it('keys the memory list by the active workspace, so switching workspace cannot show the previous one', async () => {
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    expect(mocks.capturedQueryKeys[0]).toEqual(['org', 'org-alpha', 'memories', 'list']);

    cleanup();
    mocks.capturedQueryKeys.length = 0;
    mocks.currentOrgId = 'org-beta';
    mocks.list.mockResolvedValue([]);

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByTestId('empty')).toBeInTheDocument());

    expect(mocks.capturedQueryKeys[0]).toEqual(['org', 'org-beta', 'memories', 'list']);

    // The two workspaces occupy different cache slices, which is what stops one
    // serving the other's rows. The KEY is the whole observable here: react-query
    // is stubbed in this file, so there is no real cache to catch reusing, and
    // asserting that the second render does not show the first workspace's row
    // proved nothing - the mock returns [] for it either way. What a shared key
    // WOULD do is skip the refetch, so that is the second thing checked.
    expect(mocks.list).toHaveBeenCalledTimes(2);
  });

  it('falls back to a personal-workspace cache slice rather than an unkeyed one', async () => {
    mocks.currentOrgId = null;
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    expect(mocks.capturedQueryKeys[0]).toEqual(['org', '__personal__', 'memories', 'list']);
  });

  it('shows a VIEWER no create button and no per-row edit, pin or delete action', async () => {
    mocks.canMutate = false;
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    expect(screen.queryByText('createButton')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('editAction')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('deleteAction')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('pinAction')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('deactivateAction')).not.toBeInTheDocument();
  });

  it('gives a member the write affordances the viewer is denied', async () => {
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    expect(screen.getByText('createButton')).toBeInTheDocument();
    expect(screen.getByLabelText('editAction')).toBeInTheDocument();
    expect(screen.getByLabelText('deleteAction')).toBeInTheDocument();
  });

  it('invalidates only its own list, not every org-scoped query in the app', async () => {
    const { fireEvent } = await import('@testing-library/react');
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    mocks.invalidateQueries.mockClear();
    fireEvent.click(screen.getByLabelText('pinAction'));
    await waitFor(() => expect(mocks.invalidateQueries).toHaveBeenCalled());

    // ['org'] alone would refetch workflows, agents and conversations on every
    // pin toggle, which is a whole-app refresh disguised as a checkbox.
    expect(mocks.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['org', 'org-alpha', 'memories'],
    });
  });

  it('labels each entry with its scope and who wrote it, so a human can audit the list', async () => {
    mocks.list.mockResolvedValue([
      memory({ id: 'm1', title: 'Shared fact', scope: 'workspace', source: 'agent' }),
      memory({ id: 'm2', title: 'Private fact', scope: 'agent', agentId: 'a1', source: 'user' }),
    ]);

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Shared fact')).toBeInTheDocument());

    expect(screen.getByText('scopeWorkspace')).toBeInTheDocument();
    expect(screen.getByText('scopeAgent')).toBeInTheDocument();
    expect(screen.getByText('sourceAgent')).toBeInTheDocument();
    expect(screen.getByText('sourceUser')).toBeInTheDocument();
  });
  it('asks before deleting, and does not delete while the question is still open', async () => {
    const { fireEvent } = await import('@testing-library/react');
    const { memoryService } = await import('@/lib/api/orchestrator/memory.service');
    vi.mocked(memoryService.remove).mockClear();

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('deleteAction'));

    // Deleting a memory is not undoable and the row may hold something a person
    // wrote. A single misplaced click used to destroy it; now the click only opens
    // the platform's confirm modal, and nothing has been sent yet.
    expect(screen.getByText('deleteTitle')).toBeInTheDocument();
    expect(memoryService.remove).not.toHaveBeenCalled();
  });

  it('deletes once the person confirms in the modal', async () => {
    const { fireEvent } = await import('@testing-library/react');
    const { memoryService } = await import('@/lib/api/orchestrator/memory.service');
    vi.mocked(memoryService.remove).mockClear();

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('deleteAction'));
    fireEvent.click(screen.getByText('delete'));

    await waitFor(() => expect(memoryService.remove).toHaveBeenCalledWith('m1'));
  });

  it('sends nothing when the person cancels', async () => {
    const { fireEvent } = await import('@testing-library/react');
    const { memoryService } = await import('@/lib/api/orchestrator/memory.service');
    vi.mocked(memoryService.remove).mockClear();

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('deleteAction'));
    fireEvent.click(screen.getByText('cancel'));

    expect(screen.queryByText('deleteTitle')).not.toBeInTheDocument();
    expect(memoryService.remove).not.toHaveBeenCalled();
  });

  it('offers deactivation as the reversible alternative, and sends the flag rather than deleting', async () => {
    const { fireEvent } = await import('@testing-library/react');
    const { memoryService } = await import('@/lib/api/orchestrator/memory.service');
    // The service mocks live in the module factory and so accumulate across tests
    // in this file; the assertion below is about THIS click.
    vi.mocked(memoryService.remove).mockClear();
    vi.mocked(memoryService.update).mockClear();

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('deactivateAction'));

    // Switching an entry off keeps the row, its text and its history, and takes
    // it out of every agent-facing read. It is the half of "this fact is wrong"
    // that can be undone.
    await waitFor(() => expect(memoryService.update).toHaveBeenCalledWith('m1', { isActive: false }));
    expect(memoryService.remove).not.toHaveBeenCalled();
  });

  it('shows a switched-off entry as inactive and offers to bring it back, rather than hiding it', async () => {
    const { fireEvent } = await import('@testing-library/react');
    const { memoryService } = await import('@/lib/api/orchestrator/memory.service');
    vi.mocked(memoryService.update).mockClear();
    mocks.list.mockResolvedValue([memory({ isActive: false })]);

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    // This screen is the only place a deactivated entry can be switched back on.
    // Hiding it here would strand it: invisible to the agents by design, and
    // invisible to the person who could revive it by accident.
    expect(screen.getByText('inactiveBadge')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('activateAction'));
    await waitFor(() => expect(memoryService.update).toHaveBeenCalledWith('m1', { isActive: true }));
  });
  it('searches on the server, so a word that appears only in a body is findable', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.list.mockResolvedValue([memory({ id: 'm1', title: 'Release cadence', summary: 'No mention here.' })]);
    mocks.search.mockResolvedValue([
      memory({ id: 'm2', title: 'Retention policy', summary: 'No mention here either.',
        content: 'Kelpwood invoices are kept for seven years.' }),
    ]);

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'Kelpwood' } });

    // The word is in neither title nor summary. Filtering the loaded rows in the
    // browser, which is what this did before, could never find it - the person
    // searching their own memory came up empty while an agent found it at once.
    // waitFor also covers the 300ms debounce: the request is deliberately not
    // fired on the keystroke.
    await waitFor(() => expect(mocks.search).toHaveBeenCalledWith('Kelpwood'), { timeout: 3000 });
    await waitFor(() => expect(screen.getByText('Retention policy')).toBeInTheDocument());
    expect(screen.queryByText('Release cadence')).not.toBeInTheDocument();
  });

  it('does not round-trip for a single character, which would match almost everything', async () => {
    const { fireEvent } = await import('@testing-library/react');
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'R' } });

    // Never, not even after the debounce: one character matches almost everything
    // and costs a round trip to say so.
    await new Promise(resolve => setTimeout(resolve, 500));
    expect(mocks.search).not.toHaveBeenCalled();
    // and the browser filter still narrows on what is loaded
    expect(screen.getByText('Release cadence')).toBeInTheDocument();
  });

  it('does not claim "no matches" while the search is still out', async () => {
    const { fireEvent } = await import('@testing-library/react');
    // A search that never settles: the state the component is in for the 300ms
    // debounce plus the round trip.
    mocks.search.mockReturnValue(new Promise(() => {}));

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'Kelpwood' } });

    // "No memory matches" is a claim about what the workspace CONTAINS. Made
    // before the query has answered it is a claim nobody has checked, and the
    // person reads it as the answer.
    await waitFor(() => expect(screen.getByText('loading')).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.queryByText('noMatches')).not.toBeInTheDocument();
  });

  it('says "no matches" once the search has actually answered with nothing', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.search.mockResolvedValue([]);

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'Kelpwood' } });

    // The other half: suppressing the message while loading must not suppress it
    // altogether, or the person never learns their search found nothing.
    await waitFor(() => expect(screen.getByText('noMatches')).toBeInTheDocument(), { timeout: 3000 });
  });

  it('retries the SEARCH when it was the search that failed, not the list', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.refetch.mockClear();
    mocks.search.mockRejectedValue(new Error('offline'));

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'Kelpwood' } });
    await waitFor(() => expect(screen.getByText('loadFailedTitle')).toBeInTheDocument(), { timeout: 3000 });

    mocks.search.mockResolvedValue([]);
    fireEvent.click(screen.getByText('retry'));

    // Wired to the list either way, the button silently did nothing when it was
    // the search that broke: it refetched rows that were already there and left
    // the error on screen.
    expect(mocks.refetch).toHaveBeenCalled();
  });

  it('says the list FAILED instead of drawing an empty workspace', async () => {
    mocks.list.mockRejectedValue(new Error('offline'));

    render(<MemoryTab />);

    // "Empty" and "failed to load" look identical on a feature whose list is
    // legitimately empty at first. On the one screen whose purpose is to show a
    // person everything the agents know, saying "nothing here" when the fetch
    // died is the wrong answer to the only question being asked.
    await waitFor(() => expect(screen.getByText('loadFailedTitle')).toBeInTheDocument());
    expect(screen.getByText('loadFailedSubtitle')).toBeInTheDocument();
    expect(screen.queryByText('emptyTitle')).not.toBeInTheDocument();
  });

  it('offers a retry on that failure, because the usual cause is transient', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.refetch.mockClear();
    mocks.list.mockRejectedValue(new Error('offline'));

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('loadFailedTitle')).toBeInTheDocument());
    fireEvent.click(screen.getByText('retry'));

    expect(mocks.refetch).toHaveBeenCalled();
  });

  it('says a failed SEARCH failed, rather than reporting no matches', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.search.mockRejectedValue(new Error('offline'));

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'Kelpwood' } });

    // "No memory matches" is a claim about the workspace's contents. Making it
    // when the query never returned tells the person a fact is not there when
    // nobody looked.
    await waitFor(() => expect(screen.getByText('loadFailedTitle')).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.queryByText('noMatches')).not.toBeInTheDocument();
  });

  it('fetches the body before opening the editor, instead of editing the listed row', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.get.mockResolvedValue(memory({ content: 'Freeze starts Wednesday 14:00 UTC.' }));

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('editAction'));

    // The list endpoint leaves the body out, so the row in hand has none. Opening
    // the editor on that row would show an empty body and then save the emptiness
    // over the real one - silently, and over exactly the field the person came to
    // read.
    await waitFor(() => expect(mocks.get).toHaveBeenCalledWith('m1'));
    await waitFor(() =>
      expect(screen.getByTestId('editor')).toHaveTextContent('Freeze starts Wednesday 14:00 UTC.'));
  });

  it('does not open an editor at all when the body cannot be fetched', async () => {
    const { fireEvent } = await import('@testing-library/react');
    mocks.get.mockRejectedValue(new Error('offline'));

    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('editAction'));

    // Opening an editor whose body failed to load is the same destructive shape as
    // opening it on the listed row: the person sees an empty field and saves it.
    await waitFor(() => expect(mocks.get).toHaveBeenCalled());
    expect(screen.queryByTestId('editor')).not.toBeInTheDocument();
  });
});
