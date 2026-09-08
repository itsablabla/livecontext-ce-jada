/**
 * @vitest-environment jsdom
 *
 * Regression test for the first-message startup gap: a new conversation starts
 * streaming under a temporary id before the real conversationId exists. The
 * composer must keep rendering Stop during that handoff, not fall back to a
 * disabled Send button.
 */
import { afterEach, describe, it, expect, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

// The Generate control leads to the studio through the LOCALE-AWARE router, and next-intl's
// navigation module cannot resolve 'next/navigation' under vitest. Stood in for here because this
// suite is not about where that control goes (that is pinned in the generate-entry-point suites).
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, prefetch: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: null }),
}));

vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));

vi.mock('@/hooks/useMobileDetection', () => ({
  useMobileDetection: () => false,
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAgentByConversationId: vi.fn(),
  },
}));

vi.mock('../AttachmentHandler', () => ({
  AttachmentHandler: () => null,
}));

import { MessageComposer } from '../MessageComposer';

afterEach(cleanup);

describe('MessageComposer stop button', () => {
  it('shows Stop while a first conversation stream is starting without a conversationId', () => {
    const onStopStream = vi.fn();

    render(
      <MessageComposer
        inputValue=""
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        isStreamStarting
        onStopStream={onStopStream}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    const stopButton = screen.getByTitle('chat.stop') as HTMLButtonElement;

    expect(stopButton.disabled).toBe(false);
    expect(screen.queryByTitle('chat.send')).toBeNull();

    fireEvent.click(stopButton);

    expect(onStopStream).toHaveBeenCalledTimes(1);
  });

  it('is ROUND while stopping - the one deliberate exception to the radius ladder', () => {
    // Everything else in the app is square-rounded (components/ui/README.md).
    // This button is exempt on purpose, and the exemption covers ALL of its
    // states: Send, Stop and greyed out. Squaring any of them would be a
    // regression, not a cleanup.
    render(
      <MessageComposer
        inputValue=""
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        isStreamStarting
        onStopStream={() => undefined}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    expect(screen.getByTitle('chat.stop').className).toContain('rounded-full');
  });

  it('stays round in its Send state, so the shape does not flip mid-conversation', () => {
    render(
      <MessageComposer
        inputValue="hello"
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    const sendButton = screen.getByTitle('chat.send');

    expect(sendButton.className).toContain('rounded-full');
    expect(sendButton.className).not.toContain('rounded-xl');
  });

  it('tells Send from Stop by COLOUR, not only by the glyph', () => {
    // The two states used to be the `default` and `contrast` variants: two solid
    // dark buttons a shade apart, so an arrow and a square were the whole
    // difference. Removing `contrast` would have collapsed them into one fill,
    // which is why Stop is now destructive - the same pairing the builder's
    // empty-canvas composer uses.
    render(
      <MessageComposer
        inputValue=""
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        isStreamStarting
        onStopStream={() => undefined}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    expect(screen.getByTitle('chat.stop').getAttribute('data-variant')).toBe('destructive');

    cleanup();

    render(
      <MessageComposer
        inputValue="hello"
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    const send = screen.getByTitle('chat.send');

    expect(send.getAttribute('data-variant')).toBe('default');
    // The app's one solid fill, in tokens - not a second hardcoded dark button.
    expect(send.className).toContain('bg-[var(--accent-primary)]');
    expect(send.className).not.toContain('bg-black');
  });

  it('stays round while greyed out, which is the state it spends most time in', () => {
    // An empty composer disables Send. That greyed button used to be the one
    // square-rounded state of the control, so the shape changed the moment you
    // typed the first character.
    render(
      <MessageComposer
        inputValue=""
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    const sendButton = screen.getByTitle('chat.send') as HTMLButtonElement;

    expect(sendButton.disabled).toBe(true);
    expect(sendButton.className).toContain('rounded-full');
    expect(sendButton.className).not.toContain('rounded-xl');
  });
});
