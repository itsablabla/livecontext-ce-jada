// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The picker pulls the whole storage explorer (and its data hook) in. These tests are about the
// cell's own behaviour, so stand it in with a button that reports one pick.
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({
  StorageExplorerTab: ({ onSelect }: { onSelect?: (entry: unknown) => void }) => (
    <>
      <button
        type="button"
        onClick={() =>
          onSelect?.({
            id: '99999999-9999-9999-9999-999999999999',
            fileName: 'picked.pdf',
            mimeType: 'application/pdf',
            sizeBytes: 10,
            s3Key: 't/general/x_picked.pdf',
          })
        }
      >
        pick-one
      </button>
      {/* The picker lists folders too, and a folder row can reach onSelect. */}
      <button
        type="button"
        onClick={() =>
          onSelect?.({ id: 'folder-1', fileName: 'Reports', isFolder: true, mimeType: null })
        }
      >
        pick-folder
      </button>
    </>
  ),
}));


// Authenticated image previews fetch bytes; hand back a stable object URL instead.
vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  // A distinct blob per source, as the real hook produces - so a test cannot pass by accident
  // just because two different files happened to share one preview URL.
  useAuthedObjectUrl: (url: string | null) => ({ url: url ? `blob:${url}` : null, error: false }),
}));

const uploadGeneric = vi.fn();
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  fileService: { uploadGeneric: (...args: unknown[]) => uploadGeneric(...args) },
}));

import { AssetCell } from '../cells/AssetCell';

const UUID = '44444444-4444-4444-4444-444444444444';

function renderCell(props: Partial<React.ComponentProps<typeof AssetCell>> = {}) {
  const onSaveAndExit = vi.fn();
  render(
    <AssetCell
      value={null}
      rowKey="r1"
      field="photo"
      isEditing={false}
      onSaveAndExit={onSaveAndExit}
      onStartEditing={() => {}}
      onExitEditing={() => {}}
      {...props}
    />,
  );
  return { onSaveAndExit };
}


const OTHER_UUID = '55555555-5555-5555-5555-555555555555';

/** Render a thumbnail cell and expose a rerender that swaps only the value. */
function renderCellFor(value: unknown) {
  const props = {
    rowKey: 'r1', field: 'photo', isEditing: false,
    onSaveAndExit: vi.fn(), onStartEditing: () => {}, onExitEditing: () => {},
    displayConfig: { render: 'thumbnail' as const },
  };
  const view = render(<AssetCell value={value} {...props} />);
  return {
    rerender: (next: unknown) => view.rerender(<AssetCell value={next} {...props} />),
  };
}

afterEach(() => {
  cleanup();
  uploadGeneric.mockReset();
});

describe('AssetCell', () => {
  it('shows the empty placeholder that matches the column display', () => {
    renderCell({ value: null, displayConfig: { render: 'thumbnail' } });
    expect(screen.getByText('noImage')).toBeInTheDocument();

    cleanup();

    renderCell({ value: null, displayConfig: { render: 'card' } });
    expect(screen.getByText('noFile')).toBeInTheDocument();
  });

  it('renders a stored asset by its file name', () => {
    renderCell({ value: { _type: 'file', id: UUID, name: 'invoice.pdf', mimeType: 'application/pdf', size: 2048 } });

    expect(screen.getByText('invoice.pdf')).toBeInTheDocument();
  });

  it('reads the JSON-string encoding the CRUD write path persists', () => {
    renderCell({ value: JSON.stringify({ _type: 'file', id: UUID, name: 'from-json.pdf' }) });

    expect(screen.getByText('from-json.pdf')).toBeInTheDocument();
  });

  it('says so when the reference cannot be resolved, instead of rendering an empty cell', () => {
    // A file deleted from Files, or a cell written before the file-URL cutover, used to look
    // exactly like a cell nobody had filled in. That silence is the bug being fixed.
    renderCell({ value: { _type: 'file', path: '1/general/general/ab_gone.txt', name: 'gone.txt' } });

    expect(screen.getByText('gone.txt')).toBeInTheDocument();
    expect(screen.queryByText('noFile')).not.toBeInTheDocument();
  });

  it('marks an external link as such rather than showing a fake size', () => {
    renderCell({ value: 'https://cdn.example.com/photo.png' });

    expect(screen.getByText('assetExternal')).toBeInTheDocument();
  });

  it('offers the three sources when editing', () => {
    renderCell({ isEditing: true });

    expect(screen.getByTitle('upload')).toBeInTheDocument();
    expect(screen.getByTitle('pickFromFiles')).toBeInTheDocument();
    expect(screen.getByTitle('assetUrl')).toBeInTheDocument();
  });

  it('stores the canonical asset when a file is picked from Files', async () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('pickFromFiles'));
    // The picker is loaded lazily, so it only exists after the dialog opens.
    fireEvent.click(await screen.findByText('pick-one'));

    expect(onSaveAndExit).toHaveBeenCalledWith(
      expect.objectContaining({
        _type: 'file',
        id: '99999999-9999-9999-9999-999999999999',
        name: 'picked.pdf',
        path: 't/general/x_picked.pdf',
      }),
    );
  });

  it('stores an external URL the user pastes', () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), {
      target: { value: 'https://example.com/a.png' },
    });
    fireEvent.click(screen.getByText('assetUrlConfirm'));

    expect(onSaveAndExit).toHaveBeenCalledWith(
      expect.objectContaining({ _type: 'file', url: 'https://example.com/a.png' }),
    );
  });

  it('leaves the link row by the back button, so picking "use a link" is not a one-way door', () => {
    // The complaint this fixes: the three sources are replaced by the URL row, and the only way
    // back used to be an Escape key nothing on screen mentioned.
    renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    expect(screen.queryByTitle('upload')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTitle('assetUrlBack'));

    expect(screen.getByTitle('upload')).toBeInTheDocument();
    expect(screen.getByTitle('pickFromFiles')).toBeInTheDocument();
    expect(screen.getByTitle('assetUrl')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('assetUrlPlaceholder')).not.toBeInTheDocument();
  });

  it('drops the rejected-URL message on the way back, so it cannot blame the upload button', () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), { target: { value: 'not a url' } });
    fireEvent.click(screen.getByText('assetUrlConfirm'));
    expect(screen.getByText('assetUrlInvalid')).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('assetUrlBack'));

    expect(screen.queryByText('assetUrlInvalid')).not.toBeInTheDocument();
    // Going back is not a write: nothing was chosen, so nothing is stored.
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it('still accepts Escape as the keyboard route back', () => {
    renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.keyDown(screen.getByPlaceholderText('assetUrlPlaceholder'), { key: 'Escape' });

    expect(screen.getByTitle('upload')).toBeInTheDocument();
  });

  it('re-opens the link row on an empty draft after a round trip, never on the last typed URL', () => {
    // Back has to CLEAR the draft, not hide it: coming back to a URL the cell already refused
    // (or to one the user abandoned) reads as if it had been stored.
    renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), {
      target: { value: 'https://example.com/abandoned.png' },
    });
    fireEvent.click(screen.getByTitle('assetUrlBack'));
    fireEvent.click(screen.getByTitle('assetUrl'));

    expect(screen.getByPlaceholderText('assetUrlPlaceholder')).toHaveValue('');
  });

  it('refuses a value that is not a usable URL, and stores nothing', () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), { target: { value: 'not a url' } });
    fireEvent.click(screen.getByText('assetUrlConfirm'));

    expect(screen.getByText('assetUrlInvalid')).toBeInTheDocument();
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it('hides the destructive actions in read-only mode', () => {
    renderCell({ value: { _type: 'file', id: UUID, name: 'a.pdf' }, readOnly: true });

    expect(screen.queryByTitle('removeFile')).not.toBeInTheDocument();
    expect(screen.queryByTitle('download')).not.toBeInTheDocument();
  });

  it('stores the uploaded file, keeping the storage key the publication copier needs', async () => {
    uploadGeneric.mockResolvedValue({
      id: UUID, storageKey: 't/general/datatable/ab_shot.png',
      fileName: 'shot.png', mimeType: 'image/png', size: 77,
    });
    const { onSaveAndExit } = renderCell({ isEditing: true });

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'shot.png', { type: 'image/png' })] } });

    await waitFor(() => expect(onSaveAndExit).toHaveBeenCalled());
    expect(onSaveAndExit).toHaveBeenCalledWith(
      expect.objectContaining({
        _type: 'file', id: UUID, name: 'shot.png',
        path: 't/general/datatable/ab_shot.png', size: 77,
      }),
    );
  });

  it('reports a failed upload and stores nothing', async () => {
    uploadGeneric.mockRejectedValue(new Error('quota exceeded'));
    const { onSaveAndExit } = renderCell({ isEditing: true });

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'shot.png', { type: 'image/png' })] } });

    expect(await screen.findByText('quota exceeded')).toBeInTheDocument();
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it('constrains the file input to images only on a thumbnail column', () => {
    renderCell({ isEditing: true, displayConfig: { render: 'thumbnail' } });

    expect(document.querySelector('input[type="file"]')).toHaveAttribute('accept', 'image/*');
  });

  it('accepts any file on a card column', () => {
    renderCell({ isEditing: true, displayConfig: { render: 'card' } });

    expect(document.querySelector('input[type="file"]')).not.toHaveAttribute('accept');
  });

  it('clears the cell when the file is removed', () => {
    const { onSaveAndExit } = renderCell({ value: { _type: 'file', id: UUID, name: 'a.pdf' } });

    fireEvent.click(screen.getByTitle('removeFile'));

    expect(onSaveAndExit).toHaveBeenCalledWith('');
  });

  it('renders the image itself on a thumbnail column', () => {
    renderCell({
      value: { _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png' },
      displayConfig: { render: 'thumbnail' },
    });

    expect(screen.getByAltText('a.png')).toBeInTheDocument();
  });

  it('shows the replacement image after a broken one, instead of latching on the icon', () => {
    // The fallback is keyed by the URL that failed, not a boolean: the grid re-renders this
    // component instance rather than remounting it, so a latch would keep hiding every later
    // image in that cell until the page was reloaded.
    const { rerender } = renderCellFor({ _type: 'file', id: UUID, name: 'broken.png', mimeType: 'image/png' });
    fireEvent.error(screen.getByAltText('broken.png'));
    expect(screen.queryByAltText('broken.png')).not.toBeInTheDocument();

    rerender({ _type: 'file', id: OTHER_UUID, name: 'fixed.png', mimeType: 'image/png' });

    expect(screen.getByAltText('fixed.png')).toBeInTheDocument();
  });

  it('renders an extension-less external image on a thumbnail column', () => {
    // Found live: a picsum/unsplash/signed-CDN link has neither a mime type nor an extension, so
    // the mime heuristic said "not an image" and the cell showed a generic file icon. A thumbnail
    // column IS an image column; attempting the image is safe because onError falls back.
    renderCell({ value: 'https://picsum.photos/seed/e2e/80/80', displayConfig: { render: 'thumbnail' } });

    expect(screen.getByRole('img')).toHaveAttribute('src', 'https://picsum.photos/seed/e2e/80/80');
  });

  it('does not guess that a pdf is an image on a card column', () => {
    renderCell({
      value: { _type: 'file', id: UUID, name: 'a.pdf', mimeType: 'application/pdf' },
      displayConfig: { render: 'card' },
    });

    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('falls back to the type icon when the image cannot be decoded', () => {
    // A URL can resolve and still fail to render. Showing a broken-image glyph in a grid row is
    // worse than showing what kind of file it is.
    renderCell({
      value: { _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png' },
      displayConfig: { render: 'thumbnail' },
    });

    fireEvent.error(screen.getByAltText('a.png'));

    expect(screen.queryByAltText('a.png')).not.toBeInTheDocument();
  });

  it('draws the thumbnail as a rounded square, never a circle', () => {
    // A circle crops the sides off anything that is not a portrait, which is most of what a
    // table holds. Pinned in both places (see the modal preview test) because the change states
    // the two shapes must not drift.
    const { container } = render(
      <AssetCell
        value={{ _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png' }}
        rowKey="r1"
        field="photo"
        isEditing={false}
        onSaveAndExit={vi.fn()}
        onStartEditing={() => {}}
        onExitEditing={() => {}}
        displayConfig={{ render: 'thumbnail' }}
      />,
    );

    expect(container.querySelector('.rounded-xl')).not.toBeNull();
    expect(container.querySelector('.rounded-full')).toBeNull();
  });

  it('never stores a folder as if it were a file', () => {
    // The picker lists folders now, so a folder row can reach the select handler. Storing one
    // would put a reference with no bytes behind it into the cell.
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('pickFromFiles'));
    return screen.findByText('pick-folder').then((btn) => {
      fireEvent.click(btn);
      expect(onSaveAndExit).not.toHaveBeenCalled();
    });
  });
});
