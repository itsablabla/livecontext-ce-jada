// @vitest-environment jsdom
/**
 * The Files header carries three actions - Generate, New folder, Upload - and
 * their labels are `whitespace-nowrap`, so side by side they were simply wider
 * than a phone: on a 375px viewport the row pushed the page sideways and Upload
 * ran off the right edge while the title was crushed to a single letter.
 *
 * <p>The fix drops each LABEL below `sm` and keeps the icon. What must not be
 * lost in that trade is the action's NAME: an icon-only button that no longer
 * announces itself is a worse bug than the one being fixed. These tests pin both
 * halves - the word is still the accessible name, and the word is still what is
 * removed from the visible row.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
  useTranslations: () => (key: string) => key,
}));

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
    setPage: vi.fn(), setPageSize: vi.fn(), refresh: vi.fn(),
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

const api = vi.hoisted(() => ({ getModels: vi.fn() }));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: api.getModels },
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => true,
}));


import { FileBrowser } from '../FileBrowser';

function renderBrowser() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FileBrowser />
    </QueryClientProvider>,
  );
}

/** The header action row, identified by the button it always contains. */
function actionRow(): HTMLElement {
  const upload = screen.getAllByRole('button', { name: 'upload' })[0];
  return upload.parentElement as HTMLElement;
}

beforeEach(() => {
  api.getModels.mockReset();
  api.getModels.mockResolvedValue({ models: [{ model: 'seedance-2.0', kind: 'video' }], count: 1, kinds: ['video'] });
});
afterEach(() => cleanup());

describe('FileBrowser header actions on a phone', () => {
  it.each(['generate', 'newFolder', 'upload'])(
    'keeps "%s" reachable by name once its label is hidden',
    (label) => {
      renderBrowser();

      // getAllBy: upload also appears as the empty-state call to action, and
      // this is about the name existing at all, not about how many carry it.
      expect(screen.getAllByRole('button', { name: label }).length).toBeGreaterThan(0);
    },
  );

  it.each(['generate', 'newFolder', 'upload'])(
    'hides the "%s" label below the sm breakpoint, which is what makes the row fit',
    (label) => {
      renderBrowser();

      const button = screen.getAllByRole('button', { name: label })[0];
      const word = Array.from(button.querySelectorAll('span')).find((s) => s.textContent === label);

      expect(word, `the "${label}" label is not in its own element, so it cannot be dropped on a phone`)
        .toBeDefined();
      expect(word!.className).toContain('hidden');
      expect(word!.className).toContain('sm:inline');
    },
  );

  it('keeps the action row at its natural width so the title gives ground instead', () => {
    // `justify-between` alone lets whichever side has the larger natural width
    // win, and the actions' is fixed by `whitespace-nowrap`. Pinning the row
    // as non-shrinking is what makes the (truncatable) title absorb a narrow
    // screen rather than the actions leaving it.
    renderBrowser();

    expect(actionRow().className).toContain('flex-shrink-0');
  });
});
