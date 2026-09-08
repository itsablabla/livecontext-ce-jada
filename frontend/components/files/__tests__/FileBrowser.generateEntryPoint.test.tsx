// @vitest-environment jsdom
/**
 * A generation produces a FILE, so the way to start one lives where the files
 * are, next to the other way of getting one in here.
 *
 * <p>The modal first shipped mounted by nothing at all, then mounted on the
 * AI-providers screen, which is where models are CONFIGURED, not where their
 * output lands. This pins the entry point at its home: the same read-only gate
 * as upload, the list refreshed when the asset arrives, and nothing pulled into
 * this page's bundle until the dialog is opened.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, screen, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const refresh = vi.fn();
vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: () => ({
    sort: 'date' as const,
    direction: 'desc' as const,
    setSort: vi.fn(),
    entries: [] as StorageExplorerEntry[],
    totalElements: 0, totalPages: 1, currentPage: 0, pageSize: 50,
    loading: false, error: null, search: '', sourceTypeFilter: '',
    dateFrom: '', dateTo: '', fileType: '_all', parentFolderId: null,
    setSearch: vi.fn(), setSourceTypeFilter: vi.fn(), setDateFrom: vi.fn(),
    setDateTo: vi.fn(), setFileType: vi.fn(), navigateToFolder: vi.fn(),
    setPage: vi.fn(), setPageSize: vi.fn(), refresh,
  }),
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getFolderTrail: vi.fn().mockResolvedValue([]), createFolder: vi.fn(), moveEntries: vi.fn(), deleteEntries: vi.fn(), renameEntry: vi.fn() },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  MouseSensor: class {}, TouchSensor: class {}, useSensor: () => ({}), useSensors: () => [],
  pointerWithin: () => [], rectIntersection: () => [],
}));
vi.mock('../FolderCard', () => ({ FolderCard: () => null, VirtualFolderCard: () => null }));
vi.mock('../FileCard', () => ({ FileCard: () => null }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => null }));
// ---- next/navigation: the open folder now lives in the URL, so FileBrowser reads
// useSearchParams() and navigates with router.push(). A fake router records the pushes;
// tests that need to ARRIVE in a folder set searchParams before rendering.
const routerPush = vi.fn();
const routerReplace = vi.fn();
let searchParams = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: routerPush, replace: routerReplace }),
  usePathname: () => '/en/app/files',
  useSearchParams: () => searchParams,
}));

// What it TAKES to start a drag is pinned in useDragSensors' own test. Mocking the
// hook rather than widening the @dnd-kit/core double keeps that seam in one place -
// the double omits MouseSensor, and PrimaryMouseSensor extends it at module scope,
// so reaching the real hook through here fails on an unrelated concern.
vi.mock('@/lib/dnd/useDragSensors', () => ({ useDragSensors: () => [] }));

vi.mock('../FileFilterBar', () => ({ FileFilterBar: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() },
}));

// The real catalogue call, stubbed at the SERVICE. The hook, its query key and
// the 404 classification are exercised for real, because they are the thing
// under test: what the page does with each answer.
const api = vi.hoisted(() => ({ getModels: vi.fn() }));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: api.getModels },
}));

const gate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));

// The locale-aware router. next-intl's navigation module imports a bare 'next/navigation' that
// vitest cannot resolve out of its ESM build. It is stood in for so that the ASSERTION THAT NOBODY
// TRAVELS can be made at all: generating from Files happens in place, and `nav.push` staying
// untouched is what says so. This is the only double for it in this file: a second registration
// would silently replace this one.
const nav = vi.hoisted(() => ({ push: vi.fn() }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: nav.push }),
  usePathname: () => '/app/files',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));

// The dialog itself, stood in for: what it DRAWS has its own suite, and mounting the real one here
// would pull a catalogue, a form per model and a file picker into a test about an entry point.
vi.mock('@/components/chat/CreateGenerationModal', () => ({
  CreateGenerationModal: () => <div data-testid="generation-modal" />,
}));

import { ApiError } from '@/lib/api/api-client';
import { FileBrowser } from '../FileBrowser';

/** One model, which is all "the surface is there and has something" needs. */
function catalogue(models: unknown[] = [{ model: 'seedance-2.0', kind: 'video' }]) {
  return { models, count: models.length, kinds: ['video'] };
}

function renderBrowser() {
  // retry: false so a 5xx is one answer rather than four spaced by backoff -
  // this suite is about what each answer means, not about the retry policy.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FileBrowser />
    </QueryClientProvider>,
  );
}

/** The toolbar's Generate control, whatever state it is in. */
function generateButton(): HTMLButtonElement | null {
  return screen.queryByText('generate')?.closest('button') ?? null;
}

beforeEach(() => {
  gate.canMutate = true;
  refresh.mockClear();
  nav.push.mockClear();
  api.getModels.mockReset();
  api.getModels.mockResolvedValue(catalogue());
});
afterEach(() => cleanup());

describe('FileBrowser - starting a generation from where the files are', () => {
  it('offers it beside upload, which is the other way a file gets in here', () => {
    renderBrowser();

    expect(screen.getByText('generate')).toBeDefined();
    // Upload appears twice on an empty workspace (toolbar + empty state), which
    // is why this counts rather than expecting exactly one.
    expect(screen.getAllByText('upload').length).toBeGreaterThan(0);
  });

  it('sits to the LEFT of New folder, so the two ways of making a file stay together', () => {
    // Generate and Upload both put a file in the workspace; New folder does
    // not. Reading left to right the toolbar is now make / fetch / organise,
    // and Upload stays the last, primary control.
    renderBrowser();

    const labels = Array.from(document.querySelectorAll('button'))
      .map((b) => b.textContent?.trim())
      .filter((text): text is string => text === 'generate' || text === 'newFolder' || text === 'upload');

    expect(labels.indexOf('generate')).toBeLessThan(labels.indexOf('newFolder'));
    expect(labels.indexOf('newFolder')).toBeLessThan(labels.indexOf('upload'));
  });

  it('opens nothing until it is pressed', () => {
    // Hovering or focusing the control must not open anything over the list the reader is reading.
    renderBrowser();

    fireEvent.mouseEnter(screen.getByText('generate'));
    fireEvent.focus(screen.getByText('generate'));

    expect(screen.queryByTestId('generation-modal')).toBeNull();
  });

  it('opens the dialog IN PLACE, without taking the reader off the files page', async () => {
    // The asset lands in this very list, so the reader stays in front of it. Sending them to the
    // studio instead swapped the list for a thread and left them to navigate back for the one
    // thing they came for - which is what this asserts against, through a router that would
    // record any such departure.
    renderBrowser();

    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByTestId('generation-modal')).toBeDefined();
    expect(nav.push).not.toHaveBeenCalled();
  });

  it('is hidden from a read-only viewer, exactly like upload', () => {
    // Generating writes a file into the workspace, so it belongs behind the
    // same gate as the other writes and not beside the downloads.
    gate.canMutate = false;

    renderBrowser();

    expect(screen.queryByText('generate')).toBeNull();
    expect(screen.queryAllByText('upload')).toHaveLength(0);
    // And nothing is asked about a catalogue this reader could not use anyway.
    expect(api.getModels).not.toHaveBeenCalled();
  });
});

/**
 * What each answer from the catalogue MEANS is not this page's rule any more:
 * it belongs to the shared control both entry points render, and is pinned
 * there (`components/chat/__tests__/GenerateEntryButton.test.tsx`) across all
 * four answers, on a component a test can mount twice.
 *
 * <p>What is left for this page is that it renders that control at all, and asks the catalogue
 * once. Re-asserting the whole policy through a full FileBrowser render would be the same facts
 * twice, and the copy is what drifts.
 */
describe('FileBrowser - the Generate action tells the truth about the surface behind it', () => {
  it('is wired to the catalogue, not merely drawn: a 404 takes it away', async () => {
    api.getModels.mockRejectedValue(new ApiError('Not Found', 404));

    renderBrowser();

    await waitFor(() => expect(generateButton()).toBeNull());
    // Upload is untouched: this is about the generation surface, not about who
    // may write here.
    expect(screen.getAllByText('upload').length).toBeGreaterThan(0);
  });

  it('asks the catalogue once for the page, whatever else happens on it', async () => {
    // The dialog reads the SAME query key, so opening it must not cost this page a second request.
    // (The shared cache itself is pinned in the hook's own suite.)
    renderBrowser();
    await waitFor(() => expect(api.getModels).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByText('generate'));
    await screen.findByTestId('generation-modal');

    expect(api.getModels).toHaveBeenCalledTimes(1);
  });
});
