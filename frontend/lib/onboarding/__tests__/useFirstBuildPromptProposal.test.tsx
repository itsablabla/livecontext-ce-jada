// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearDraft, writeDraft } from '@/lib/chat/draftStorage';
import { track } from '@/lib/analytics/analytics';
import { PENDING_MESSAGE_KEY } from '@/hooks/chat/useMessageHandlersV2';
import {
  FIRST_BUILD_PROMPT_KEY,
  FIRST_BUILD_PROMPT_MAX_AGE_MS,
  storeFirstBuildPrompt,
} from '../firstBuildPrompt';
import { useFirstBuildPromptProposal } from '../useFirstBuildPromptProposal';

vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

const PROPOSAL = 'Build me a workflow that summarizes my inbox.';

afterEach(() => {
  cleanup();
  clearDraft(null);
  sessionStorage.clear();
  vi.restoreAllMocks();
  vi.mocked(track).mockClear();
});

describe('useFirstBuildPromptProposal', () => {
  it('fills the empty composer with what onboarding proposed', () => {
    storeFirstBuildPrompt(PROPOSAL);
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange));

    expect(onInputChange).toHaveBeenCalledWith(PROPOSAL);
  });

  it('does nothing when onboarding proposed nothing', () => {
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange));

    expect(onInputChange).not.toHaveBeenCalled();
  });

  it('waits for the home view rather than dropping the sentence into another composer', () => {
    storeFirstBuildPrompt(PROPOSAL);
    const onInputChange = vi.fn();

    const { rerender } = renderHook(
      ({ active }: { active: boolean }) => useFirstBuildPromptProposal(active, '', onInputChange),
      { initialProps: { active: false } },
    );

    expect(onInputChange).not.toHaveBeenCalled();
    // The proposal is still in the slot for the home view, not thrown away.
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toContain(PROPOSAL);

    act(() => rerender({ active: true }));

    expect(onInputChange).toHaveBeenCalledWith(PROPOSAL);
  });

  it('never overwrites what the user has already started typing', () => {
    storeFirstBuildPrompt(PROPOSAL);
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, 'my own idea', onInputChange));

    expect(onInputChange).not.toHaveBeenCalled();
    // Consumed anyway, so it cannot resurface over a later conversation.
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it('yields to a draft the composer is about to restore', () => {
    // The case the `inputValue` prop cannot see. MessageComposer restores its
    // draft in its own mount effect, and child effects run BEFORE parent
    // effects, so by the time this hook runs the draft is already on its way
    // into the composer while the prop here still reads empty. Writing the
    // proposal would destroy it.
    storeFirstBuildPrompt(PROPOSAL);
    writeDraft(null, 'a message I was in the middle of writing');
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange, null));

    expect(onInputChange).not.toHaveBeenCalled();
  });

  it('proposes when the only draft belongs to another conversation', () => {
    storeFirstBuildPrompt(PROPOSAL);
    writeDraft('conversation-42', 'unrelated draft');
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange, null));

    expect(onInputChange).toHaveBeenCalledWith(PROPOSAL);
  });

  it('reports a proposal only where one actually reached a composer', () => {
    // The event has to fire HERE and nowhere else: onboarding already records
    // that a prompt was built, and the gap between the two is the whole
    // question the metric exists to answer.
    storeFirstBuildPrompt(PROPOSAL);

    renderHook(() => useFirstBuildPromptProposal(true, '', vi.fn(), null));

    expect(track).toHaveBeenCalledWith('first_build_prompt_filled', {
      length: PROPOSAL.length,
    });
  });

  it.each([
    ['there was no proposal', () => undefined],
    ['a draft won', () => { storeFirstBuildPrompt(PROPOSAL); writeDraft(null, 'my own draft'); }],
    ['a pre-login message is about to be sent', () => {
      storeFirstBuildPrompt(PROPOSAL);
      sessionStorage.setItem(
        PENDING_MESSAGE_KEY,
        JSON.stringify({ message: 'written before signing up', timestamp: Date.now() }),
      );
    }],
  ])('reports nothing when %s', (_label, arrange) => {
    arrange();

    renderHook(() => useFirstBuildPromptProposal(true, '', vi.fn(), null));

    expect(track).not.toHaveBeenCalled();
  });

  it('stands aside for a message written before sign-up, which is auto-sent and clears the composer', () => {
    storeFirstBuildPrompt(PROPOSAL);
    sessionStorage.setItem(
      PENDING_MESSAGE_KEY,
      JSON.stringify({ message: 'written before signing up', timestamp: Date.now() }),
    );
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange, null));

    expect(onInputChange).not.toHaveBeenCalled();
    // The pending message must survive: this hook may only READ that slot.
    expect(sessionStorage.getItem(PENDING_MESSAGE_KEY)).toContain(
      'written before signing up',
    );
  });

  it('proposes when the pending pre-login message is too old to be sent', () => {
    storeFirstBuildPrompt(PROPOSAL);
    sessionStorage.setItem(
      PENDING_MESSAGE_KEY,
      JSON.stringify({ message: 'abandoned', timestamp: Date.now() - 6 * 60 * 1000 }),
    );
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange, null));

    expect(onInputChange).toHaveBeenCalledWith(PROPOSAL);
  });

  it('reports nothing for a proposal that expired before the chat was reached', () => {
    const written = 1_000_000;
    storeFirstBuildPrompt(PROPOSAL, written);
    vi.spyOn(Date, 'now').mockReturnValue(written + FIRST_BUILD_PROMPT_MAX_AGE_MS + 1);
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange, null));

    expect(onInputChange).not.toHaveBeenCalled();
    expect(track).not.toHaveBeenCalled();
  });

  it.each([
    ['is not JSON at all', 'not json'],
    ['carries no timestamp', JSON.stringify({ message: 'x' })],
    ['carries a timestamp that is not a number', JSON.stringify({ message: 'x', timestamp: 'now' })],
  ])('still proposes when the pending-message slot %s', (_label, stored) => {
    // An unusable pending slot is not a pending send, so it must not silence
    // the proposal: failing open here would lose the feature to a stray value.
    storeFirstBuildPrompt(PROPOSAL);
    sessionStorage.setItem(PENDING_MESSAGE_KEY, stored);
    const onInputChange = vi.fn();

    renderHook(() => useFirstBuildPromptProposal(true, '', onInputChange, null));

    expect(onInputChange).toHaveBeenCalledWith(PROPOSAL);
  });

  it('proposes once: typing then clearing the composer does not bring it back', () => {
    storeFirstBuildPrompt(PROPOSAL);
    const onInputChange = vi.fn();

    const { rerender } = renderHook(
      ({ value }: { value: string }) => useFirstBuildPromptProposal(true, value, onInputChange),
      { initialProps: { value: '' } },
    );

    expect(onInputChange).toHaveBeenCalledTimes(1);

    act(() => rerender({ value: 'user typed something' }));
    act(() => rerender({ value: '' }));

    expect(onInputChange).toHaveBeenCalledTimes(1);
  });
});
