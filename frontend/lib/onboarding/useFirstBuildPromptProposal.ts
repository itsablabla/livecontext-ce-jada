'use client';

import { useEffect, useRef } from 'react';
import { readDraft } from '@/lib/chat/draftStorage';
import { track } from '@/lib/analytics/analytics';
import { hasFreshPendingMessage } from '@/hooks/chat/useMessageHandlersV2';
import { consumeFirstBuildPrompt } from './firstBuildPrompt';

/**
 * Fill the chat composer, once, with the first message onboarding proposed for
 * this account (see {@link consumeFirstBuildPrompt}).
 *
 * <p>Once, and only on the home view: the proposal belongs to the empty first
 * screen. Reading it from anywhere else would drop a sentence into a composer
 * the user had opened for something else entirely.
 *
 * @param active   true on the welcome/home view - the only place a proposal makes sense
 * @param inputValue    what the composer currently holds
 * @param onInputChange the composer's setter
 * @param conversationId the draft slot to protect. Pass the composer's OWN
 *        `conversationId` prop rather than the page's, so this reads the slot
 *        the composer on screen actually reads. Today the only caller leaves
 *        that prop unset, so both are `undefined` and both resolve to the
 *        new-chat slot; the parameter exists so the coupling stays correct if
 *        the composer is ever given one, not because they differ now.
 */
export function useFirstBuildPromptProposal(
  active: boolean,
  inputValue: string,
  onInputChange: (value: string) => void,
  conversationId?: string | null,
): void {
  // The whole hook is one-shot, so `inputValue` can sit in the dependency list
  // (every later keystroke re-runs the effect and returns on the first line)
  // rather than being smuggled past it through a ref written during render.
  const consumed = useRef(false);

  useEffect(() => {
    if (consumed.current || !active) return;
    // Consume before the checks below, so a proposal that is not used is not
    // left behind to reappear over some later conversation.
    consumed.current = true;
    const proposal = consumeFirstBuildPrompt();
    if (!proposal) return;

    // Never overwrite what the user already has in the composer. Two different
    // sources of "already has", and the prop only carries one of them:
    //
    //  - typed just now: on a slow first paint the user can beat this effect,
    //    and `inputValue` shows it;
    //  - a RESTORED DRAFT: MessageComposer restores it from sessionStorage in
    //    its own mount effect. Child effects run before parent effects, so it
    //    has already called onInputChange while `inputValue` here is still the
    //    empty render-time value - the prop cannot see it, and writing the
    //    proposal would destroy the draft. So the draft store is asked
    //    directly, with the same reader (and the same TTL) the composer uses.
    //
    //  - a PENDING PRE-LOGIN MESSAGE: one written before signing up is
    //    auto-sent shortly after auth is ready, and sending clears the
    //    composer. Filling it here would lose the proposal to that clear AND
    //    report a delivery that never happened, in exactly the sign-up funnel
    //    this feature is measured on.
    if (inputValue.trim() || readDraft(conversationId) || hasFreshPendingMessage()) return;

    onInputChange(proposal);
    // Fired HERE, not where the prompt is built. Onboarding knows it wrote a
    // proposal; only this line knows one actually reached a composer, which is
    // what the feature claims to do. Between the two the proposal can expire,
    // lose to a draft, or never reach the home view at all.
    track('first_build_prompt_filled', { length: proposal.length });
  }, [active, inputValue, onInputChange, conversationId]);
}
