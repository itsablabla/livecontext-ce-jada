// @vitest-environment jsdom
/**
 * Regression tests for the Files browser's navigation and remembered preferences.
 *
 * The bug these were written for: in production the listing can take a while, the folder
 * cards of the PREVIOUS folder stay on screen, and an impatient second click pushed a
 * second navigation. The breadcrumb read "Run 12 / Run 12 / Run 12" and it took three
 * Backs to leave. Two guards fix it (already-there, and navigation-in-flight) and both
 * are pinned below - each fails on the pre-fix code, which pushed unconditionally.
 *
 * Also covered: the folder now lives in the URL (so a refresh returns to it, rebuilding
 * the breadcrumb from the server), while the view mode and sort criterion live in
 * localStorage (so they follow the user rather than a shared link).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, waitFor, act } from '@testing-library/react';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

// next-intl's navigation module cannot resolve 'next/navigation' under vitest, and this page
// renders controls that reach for it. Stood in for here because this suite is not about where any
// of them go (the Generate control is pinned in the generate-entry-point suite).
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, prefetch: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'number' in vars ? `${key}:${vars.number}` : vars && 'count' in vars ? `${key}:${vars.count}` : key,
}));

// ---- the storage hook: `loading` is controllable, and the sort setter is observed ----
const navigateToFolder = vi.fn();
const setSort = vi.fn();
const refresh = vi.fn();
let hookState: {
  entries: StorageExplorerEntry[];
  parentFolderId: string | null | undefined;
  loading: boolean;
} = { entries: [], parentFolderId: null, loading: false };
vi.mock('@/app/workflows/builder/components/inspector/useStorageExplorer', () => ({
  useStorageExplorer: () => ({
    entries: hookState.entries,
    totalElements: hookState.entries.length,
    totalPages: 1,
    currentPage: 0,
    pageSize: 50,
    loading: hookState.loading,
    error: null,
    search: '',
    sourceTypeFilter: '',
    dateFrom: '',
    dateTo: '',
    fileType: '_all',
    sort: 'date' as const,
    direction: 'desc' as const,
    setSort,
    parentFolderId: hookState.parentFolderId,
    setSearch: vi.fn(),
    setSourceTypeFilter: vi.fn(),
    setDateFrom: vi.fn(),
    setDateTo: vi.fn(),
    setFileType: vi.fn(),
    navigateToFolder,
    setPage: vi.fn(),
    setPageSize: vi.fn(),
    refresh,
  }),
}));

const getFolderTrail = vi.fn().mockResolvedValue([]);
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getFolderTrail: (...a: unknown[]) => getFolderTrail(...a),
    createFolder: vi.fn(),
    moveEntries: vi.fn(),
    deleteEntries: vi.fn(),
    deleteVirtualFolder: vi.fn(),
    renameEntry: vi.fn(),
    getAllFolders: vi.fn().mockResolvedValue([]),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

// ---- next/navigation: a fake router that records pushes ----
const routerPush = vi.fn();
const routerReplace = vi.fn();
let searchParams = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: routerPush, replace: routerReplace }),
  usePathname: () => '/en/app/files',
  useSearchParams: () => searchParams,
}));

vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  MouseSensor: class {},
  TouchSensor: class {},
  pointerWithin: () => [],
  rectIntersection: () => [],
  useSensor: () => ({}),
  useSensors: () => [],
}));

vi.mock('../FolderCard', () => ({
  FolderCard: ({ entry, onOpen, label }: { entry: StorageExplorerEntry; onOpen: (e: StorageExplorerEntry) => void; label: string }) => (
    <button data-testid={`open-folder-${entry.id}`} onClick={() => onOpen(entry)}>{label}</button>
  ),
  VirtualFolderCard: ({ entry, onOpen, label }: { entry: StorageExplorerEntry; onOpen: (e: StorageExplorerEntry) => void; label: string }) => (
    <button data-testid={`open-vfolder-${entry.virtualId}`} onClick={() => onOpen(entry)}>{label}</button>
  ),
}));
vi.mock('../FileCard', () => ({ FileCard: () => <div /> }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => <div /> }));

// The filter bar is stubbed down to the two controls under test, so the assertions are
// about what FileBrowser does with a view/sort change - not about the bar's markup.
vi.mock('../FileFilterBar', () => ({
  FileFilterBar: ({ viewMode, onViewModeChange, sortKey, onSortKeyChange, onSortDirectionToggle }: {
    viewMode: string;
    onViewModeChange: (v: 'grid' | 'list') => void;
    sortKey: string;
    onSortKeyChange: (v: 'date' | 'name' | 'size' | 'type') => void;
    onSortDirectionToggle: () => void;
  }) => (
    <div>
      <span data-testid="view-mode">{viewMode}</span>
      <span data-testid="sort-key">{sortKey}</span>
      <button data-testid="set-list" onClick={() => onViewModeChange('list')} />
      <button data-testid="sort-by-name" onClick={() => onSortKeyChange('name')} />
      <button data-testid="toggle-direction" onClick={onSortDirectionToggle} />
    </div>
  ),
}));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => <div /> }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [], isLoading: false, availability: 'absent' }),
}));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() } }));

import { FileBrowser } from '../FileBrowser';
import {
  FILES_SORT_STORAGE_KEY,
  FILES_VIEW_MODE_STORAGE_KEY,
} from '@/lib/files/filesViewPreferences';
import {
  onFilesDetailState,
  emitFilesFolderNavigate,
  type FilesDetailState,
} from '@/lib/files/filesHeaderBus';

/** A computed RUN folder - the shape the reported bug was seen on. */
function runFolder(virtualId: string, runNumber: number): StorageExplorerEntry {
  return {
    id: null, virtualId, virtualKind: 'RUN', workflowId: 'wf1', workflowName: 'xAI Video Sequence',
    isFolder: true, parentFolderId: null, childCount: 4, previewFiles: [], epoch: runNumber,
    storageType: 'S3_FILE', sourceType: null, fileName: null, mimeType: null, sizeBytes: null,
    formattedSize: '0 B', createdAt: '2026-07-21T17:51:00Z', s3Key: null, contentType: null,
  } as unknown as StorageExplorerEntry;
}

beforeEach(() => {
  hookState = { entries: [], parentFolderId: null, loading: false };
  searchParams = new URLSearchParams();
  routerPush.mockClear();
  routerReplace.mockClear();
  navigateToFolder.mockClear();
  setSort.mockClear();
  getFolderTrail.mockClear();
  getFolderTrail.mockResolvedValue([]);
  window.localStorage.clear();
});
afterEach(() => cleanup());

describe('FileBrowser - repeated clicks on the same folder', () => {
  it('navigates ONCE when the same folder card is clicked three times', () => {
    // The production symptom: a slow listing, an impatient user, and a breadcrumb that
    // grew "Run 12 / Run 12 / Run 12". Only the first click may reach the router.
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    hookState.parentFolderId = 'wf:wf1/r12';
    hookState.entries = [runFolder('wf:wf1/r12', 12)];
    const { getByTestId } = render(<FileBrowser />);

    act(() => {
      fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12'));
      fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12'));
      fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12'));
    });

    // Already inside that folder - not one push.
    expect(routerPush).not.toHaveBeenCalled();
  });

  it('pushes once, then ignores repeats of the folder now open', () => {
    hookState.entries = [runFolder('wf:wf1/r12', 12)];
    const { getByTestId, rerender } = render(<FileBrowser />);

    act(() => fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12')));
    expect(routerPush).toHaveBeenCalledTimes(1);
    expect(routerPush).toHaveBeenCalledWith('/en/app/files?folder=wf%3Awf1%2Fr12');

    // The navigation lands: the URL now names that folder.
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    rerender(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12')));
    expect(routerPush).toHaveBeenCalledTimes(1);
  });

  it('ignores a folder click while the replacement listing is still loading', () => {
    // The cards on screen belong to the folder being LEFT: acting on them would append a
    // crumb that is not a child of the current folder.
    hookState.loading = true;
    hookState.entries = [runFolder('wf:wf1/r12', 12)];
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12')));
    expect(routerPush).not.toHaveBeenCalled();
  });

  it('ignores a folder click in the window where loading is already false but the listing is stale', () => {
    // The URL has moved on and the fetch has not started yet: `loading` is false while the
    // cards still belong to the previous folder. A guard that only watched `loading` let a
    // click on a SIBLING card through here and filed its crumb under the wrong parent.
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    hookState.parentFolderId = null; // still showing the root listing
    hookState.loading = false;
    hookState.entries = [runFolder('wf:wf1/r13', 13)];
    const { getByTestId } = render(<FileBrowser />);

    act(() => fireEvent.click(getByTestId('open-vfolder-wf:wf1/r13')));

    expect(routerPush).not.toHaveBeenCalled();
  });

  it('THE BUG: the breadcrumb never stacks the same folder twice', () => {
    // The guard under test is the trail dedupe, so the router here deliberately does NOT
    // update the URL - that models the real window (a push in flight, the URL not yet
    // reflecting it) in which the pre-fix code appended a second and third "Run 12".
    hookState.entries = [runFolder('wf:wf1/r12', 12)];
    const states: FilesDetailState[] = [];
    const off = onFilesDetailState((s) => states.push(s));
    const { getByTestId } = render(<FileBrowser />);

    act(() => {
      fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12'));
      fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12'));
      fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12'));
    });

    // ONE crumb, not three. This is the breadcrumb the app header renders.
    expect(states[states.length - 1].folderTrail).toEqual([{ id: 'wf:wf1/r12', name: 'runLabel:12' }]);
    off();
  });

  it('does not push when the crumb of the folder already open is activated', async () => {
    // Driven over the header bus rather than the in-page crumb: that crumb is rendered
    // `disabled`, so clicking it never reaches the handler and would prove nothing about
    // the guard. The app-header breadcrumb IS clickable and comes through here.
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    hookState.parentFolderId = 'wf:wf1/r12';
    getFolderTrail.mockResolvedValue([
      { id: 'wf:wf1', virtualId: 'wf:wf1', virtualKind: 'WORKFLOW', workflowName: 'xAI Video Sequence' },
      { id: 'wf:wf1/r12', virtualId: 'wf:wf1/r12', virtualKind: 'RUN', epoch: 12 },
    ]);
    const { findByText } = render(<FileBrowser />);
    await findByText('runLabel:12'); // trail loaded
    routerPush.mockClear();

    act(() => emitFilesFolderNavigate('wf:wf1/r12'));

    expect(routerPush).not.toHaveBeenCalled();
  });

  it('an ANCESTOR crumb does navigate - the guard only silences the current folder', async () => {
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    hookState.parentFolderId = 'wf:wf1/r12';
    getFolderTrail.mockResolvedValue([
      { id: 'wf:wf1', virtualId: 'wf:wf1', virtualKind: 'WORKFLOW', workflowName: 'xAI Video Sequence' },
      { id: 'wf:wf1/r12', virtualId: 'wf:wf1/r12', virtualKind: 'RUN', epoch: 12 },
    ]);
    const { findByText } = render(<FileBrowser />);
    await findByText('runLabel:12');
    routerPush.mockClear();

    act(() => emitFilesFolderNavigate('wf:wf1'));

    expect(routerPush).toHaveBeenCalledWith('/en/app/files?folder=wf%3Awf1');
  });
});

describe('FileBrowser - the folder lives in the URL', () => {
  it('opens the URL folder on mount and rebuilds the breadcrumb from the server', async () => {
    // A refresh, or a pasted link: nothing but the folder id is known locally.
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    hookState.parentFolderId = 'wf:wf1/r12';
    getFolderTrail.mockResolvedValue([
      { id: 'wf:wf1', virtualId: 'wf:wf1', virtualKind: 'WORKFLOW', workflowName: 'xAI Video Sequence' },
      { id: 'wf:wf1/r12', virtualId: 'wf:wf1/r12', virtualKind: 'RUN', epoch: 12 },
    ]);
    const { findByText } = render(<FileBrowser />);

    expect(navigateToFolder).toHaveBeenCalledWith('wf:wf1/r12');
    expect(getFolderTrail).toHaveBeenCalledWith('wf:wf1/r12', { filesOnly: true, s3Only: true });
    // Both crumbs render, labelled exactly like the folder tiles are.
    expect(await findByText('xAI Video Sequence')).toBeTruthy();
    expect(await findByText('runLabel:12')).toBeTruthy();
  });

  it('does not ask the server for a trail it already has (the crumb it just pushed)', async () => {
    hookState.entries = [runFolder('wf:wf1/r12', 12)];
    const { getByTestId, rerender } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('open-vfolder-wf:wf1/r12')));
    searchParams = new URLSearchParams('folder=wf%3Awf1%2Fr12');
    rerender(<FileBrowser />);
    await waitFor(() => expect(navigateToFolder).toHaveBeenCalledWith('wf:wf1/r12'));
    expect(getFolderTrail).not.toHaveBeenCalled();
  });

  it('keeps the listing when the trail cannot be rebuilt', async () => {
    // A failed breadcrumb costs the breadcrumb, never the files.
    searchParams = new URLSearchParams('folder=wf%3Awf1');
    getFolderTrail.mockRejectedValue(new Error('boom'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<FileBrowser />);
    await waitFor(() => expect(consoleError).toHaveBeenCalled());
    expect(navigateToFolder).toHaveBeenCalledWith('wf:wf1');
    consoleError.mockRestore();
  });
});

describe('FileBrowser - remembered view + sort preferences', () => {
  it('restores the list view saved on a previous visit', () => {
    window.localStorage.setItem(FILES_VIEW_MODE_STORAGE_KEY, 'list');
    const { getByTestId } = render(<FileBrowser />);
    expect(getByTestId('view-mode').textContent).toBe('list');
  });

  it('saves the view mode when the user switches to the list', () => {
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('set-list')));
    expect(window.localStorage.getItem(FILES_VIEW_MODE_STORAGE_KEY)).toBe('list');
    expect(getByTestId('view-mode').textContent).toBe('list');
  });

  it('falls back to the grid when the saved view mode is not one we offer', () => {
    window.localStorage.setItem(FILES_VIEW_MODE_STORAGE_KEY, 'mosaic');
    const { getByTestId } = render(<FileBrowser />);
    expect(getByTestId('view-mode').textContent).toBe('grid');
  });

  it('picking a criterion adopts its natural direction and remembers both', () => {
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('sort-by-name')));
    // A→Z, because that is what "sort by name" means to a user.
    expect(setSort).toHaveBeenCalledWith('name', 'asc');
    expect(JSON.parse(window.localStorage.getItem(FILES_SORT_STORAGE_KEY) as string))
      .toEqual({ key: 'name', direction: 'asc' });
  });

  it('flipping the direction keeps the criterion and remembers the flip', () => {
    const { getByTestId } = render(<FileBrowser />);
    act(() => fireEvent.click(getByTestId('toggle-direction')));
    expect(setSort).toHaveBeenCalledWith('date', 'asc');
    expect(JSON.parse(window.localStorage.getItem(FILES_SORT_STORAGE_KEY) as string))
      .toEqual({ key: 'date', direction: 'asc' });
  });
});
