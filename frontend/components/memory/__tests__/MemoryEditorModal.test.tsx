// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MemoryEditorModal } from '@/components/memory/MemoryEditorModal';
import type { Memory } from '@/lib/api/orchestrator/memory.service';

/**
 * The editor is where a person corrects what the agents believe, so the three
 * things pinned here are the ones that decide whether a correction survives.
 *
 * The backend refuses text that reads as an instruction and answers with the
 * sentence explaining how to rewrite it. If this form swallowed that message, the
 * person would see a save that did nothing and no way to find out why, which is
 * the worst possible outcome for a refusal designed to be actionable.
 */

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

function entry(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'm-1',
    slug: 'release-cadence',
    title: 'Release cadence',
    summary: 'The team ships on Thursdays.',
    content: 'Long body about the release train.',
    type: 'project',
    tags: [],
    pinned: false,
    source: 'agent',
    scope: 'workspace',
    agentId: null,
    isActive: true,
    recallCount: 0,
    lastRecalledAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  } as Memory;
}

afterEach(cleanup);

describe('MemoryEditorModal', () => {
  it('shows the backend refusal verbatim instead of a generic failure, and keeps the form open', async () => {
    const refusal = new Error(
      "This text reads as an instruction rather than a fact. Rewrite it as something that is true, "
      + "for example 'the team prefers French' rather than 'always answer in French'.");
    const onSave = vi.fn().mockRejectedValue(refusal);
    const onClose = vi.fn();

    render(<MemoryEditorModal memory={entry()} onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(screen.getByText(refusal.message)).toBeInTheDocument());
    // Closing on a refusal would discard what the person typed along with the
    // explanation of why it was refused.
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes only once the save has actually succeeded', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();

    render(<MemoryEditorModal memory={entry()} onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  it('refuses to save without a title or a summary, because an entry with neither can never be recalled', () => {
    const onSave = vi.fn();

    render(<MemoryEditorModal memory={null} onClose={vi.fn()} onSave={onSave} />);

    // A new entry starts empty: the button has to be disabled, not merely fail on
    // the round trip. The summary is the line every agent carries on every run, so
    // an empty one costs the same as a good one and recalls nothing.
    const save = screen.getByText('save');
    expect(save).toBeDisabled();

    fireEvent.change(screen.getByLabelText('fieldTitle'), { target: { value: 'A title' } });
    expect(screen.getByText('save')).toBeDisabled();

    fireEvent.change(screen.getByLabelText('fieldSummary'), { target: { value: 'A summary' } });
    expect(screen.getByText('save')).not.toBeDisabled();
  });

  it('sends the pin state along with the rest of the form, not the pin alone', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(<MemoryEditorModal memory={entry()} onClose={vi.fn()} onSave={onSave} />);
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      pinned: true,
      title: 'Release cadence',
    }));
  });

  it('carries the body through unchanged when only the pin was touched, so an edit cannot blank it', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(<MemoryEditorModal memory={entry()} onClose={vi.fn()} onSave={onSave} />);
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    // The form posts every field on every save, so the body has to be the stored
    // one. Sending an empty string here would erase an 8000-character entry for
    // someone who only wanted to pin it.
    expect(onSave.mock.calls[0][0].content).toBe('Long body about the release train.');
  });
});

describe('MemoryEditorModal - dialog behaviour', () => {
  afterEach(cleanup);

  it('announces itself as a modal dialog, labelled by its own heading', () => {
    render(<MemoryEditorModal memory={null} onClose={() => {}} onSave={async () => {}} />);

    // Without these a screen reader announces the form as ordinary page content
    // and reads the memory list behind it as if it were still reachable. The
    // platform's other confirm dialog sets both.
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('aria-labelledby', 'memory-editor-title');
    expect(document.getElementById('memory-editor-title')).toBeInTheDocument();
  });

  it('closes on Escape, so it is not dismissible only by finding its X', async () => {
    const onClose = vi.fn();
    render(<MemoryEditorModal memory={null} onClose={onClose} onSave={async () => {}} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });

  it('ignores Escape while a save is in flight, so nobody is left unsure whether it landed', async () => {
    const onClose = vi.fn();
    // A save that never settles, which is what "in flight" means here.
    const onSave = vi.fn().mockReturnValue(new Promise(() => {}));
    render(<MemoryEditorModal memory={null} onClose={onClose} onSave={onSave} />);

    fireEvent.change(screen.getByLabelText('fieldTitle'), { target: { value: 'A title' } });
    fireEvent.change(screen.getByLabelText('fieldSummary'), { target: { value: 'A summary.' } });
    fireEvent.click(screen.getByText('save'));
    await waitFor(() => expect(onSave).toHaveBeenCalled());

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('removes its key listener on unmount, so a closed dialog cannot still swallow Escape', () => {
    const onClose = vi.fn();
    const { unmount } = render(
      <MemoryEditorModal memory={null} onClose={onClose} onSave={async () => {}} />);

    unmount();
    fireEvent.keyDown(document, { key: 'Escape' });

    // A document-level listener that outlives its component fires for every
    // Escape on the page afterwards, calling a callback whose owner is gone.
    expect(onClose).not.toHaveBeenCalled();
  });
});
