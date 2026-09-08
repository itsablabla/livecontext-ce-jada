/**
 * @vitest-environment jsdom
 *
 * The onboarding proposal reaches the composer through the real ChatPageLayout.
 *
 * <p>The hook itself is unit-tested; what only this file can show is that the
 * layout wires it to the right things: the home-view flag, the composer's
 * setter, and the composer's OWN `conversationId`, which is the draft slot that
 * must not be trampled.
 *
 * <p><strong>About the composer stub.</strong> The real MessageComposer is not
 * rendered (it drags in the model picker, attachments and chat config). Its
 * stub instead reproduces the ONE behaviour that interacts with the proposal:
 * restoring its draft from the shared store in a MOUNT effect, which React runs
 * BEFORE the parent's effect. That ordering is the whole reason the hook asks
 * the draft store directly instead of trusting the `inputValue` prop, so a stub
 * that skipped it would make the regression untestable. It uses the real
 * `readDraft`, against the real slot key.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { clearDraft, readDraft, writeDraft } from '@/lib/chat/draftStorage';
import { FIRST_BUILD_PROMPT_KEY, storeFirstBuildPrompt } from '@/lib/onboarding/firstBuildPrompt';

const PROPOSAL = 'Build me a workflow that summarizes my inbox.';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
  useRouter: () => ({ push: () => undefined }),
  usePathname: () => '/app',
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => ({ isOpen: false }) }));
vi.mock('@/contexts/SidePanelLayoutContext', () => ({
  useSidePanelLayoutSafe: () => ({ position: 'right' }),
}));
vi.mock('@/contexts/ConversationActivityContext', () => ({
  useConversationActivity: () => ({ isOpen: false, setOpen: vi.fn() }),
}));
// Settable per test: the data-source arm of the render chain is selected by
// this hook, and a module-scope constant would make that arm untestable.
let currentView = { view: 'chat', dataSourceId: null as string | null };
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => currentView,
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

vi.mock('@/components/chat/ToolSelector', () => ({ ToolSelector: () => <div /> }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div /> }));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/chat/DashboardContent', () => ({ DashboardContent: () => <div /> }));
vi.mock('@/components/chat/HighlightedApps', () => ({ HighlightedApps: () => <div /> }));
vi.mock('@/components/chat/HomeDynamicTitle', () => ({ HomeDynamicTitle: () => <div /> }));
vi.mock('@/components/chat/HomeSuggestionChips', () => ({ HomeSuggestionChips: () => <div /> }));
vi.mock('@/components/chat/DataSourceMessage', () => ({
  DataSourceMessage: () => <div />,
  isDataSourceMessage: () => false,
}));
vi.mock('@/components/chat/ConversationActivityCard', () => ({
  ConversationActivityCard: () => <div />,
}));

// The composer stub: restores its draft in a mount effect, exactly as the real
// one does, and shows whatever value it currently holds.
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({
    inputValue,
    onInputChange,
    conversationId,
  }: {
    inputValue: string;
    onInputChange: (value: string) => void;
    conversationId?: string | null;
  }) => {
    const restored = React.useRef(false);
    React.useEffect(() => {
      if (restored.current) return;
      restored.current = true;
      const draft = readDraft(conversationId);
      if (draft) onInputChange(draft);
    }, [conversationId, onInputChange]);
    return <div data-testid="composer" data-value={inputValue} />;
  },
}));

import { ChatPageLayout } from '../ChatPageLayout';

function Harness({
  showWelcomeMessage,
  composerConversationId,
  pageConversationId,
  dashboardPath,
  enableDataSource,
}: {
  showWelcomeMessage: boolean;
  composerConversationId?: string | null;
  pageConversationId?: string | null;
  dashboardPath?: string | null;
  enableDataSource?: boolean;
}) {
  const [inputValue, setInputValue] = React.useState('');
  return (
    <ChatPageLayout
      toolSelectorProps={{} as never}
      messageHistoryProps={{ messages: [] } as never}
      composerProps={{
        inputValue,
        onInputChange: setInputValue,
        conversationId: composerConversationId,
      } as never}
      conversationId={pageConversationId}
      dashboardPath={dashboardPath}
      enableDataSource={enableDataSource}
      layoutState={{
        showWelcomeMessage,
        shouldRenderHistory: false,
        isConversationActive: false,
        isLoadingConversation: false,
        messagesContainerRef: React.createRef<HTMLDivElement>(),
        streamLastError: null,
        attemptStreamReconnection: () => undefined,
      }}
    />
  );
}

const composerValue = () => screen.getAllByTestId('composer')[0].getAttribute('data-value');

beforeEach(() => {
  sessionStorage.clear();
  currentView = { view: 'chat', dataSourceId: null };
});

afterEach(() => {
  cleanup();
  clearDraft(null);
  sessionStorage.clear();
});

describe('ChatPageLayout first-build proposal', () => {
  it('fills the composer on the home view', () => {
    storeFirstBuildPrompt(PROPOSAL);

    render(<Harness showWelcomeMessage />);

    expect(composerValue()).toBe(PROPOSAL);
  });

  it('leaves a restored draft alone', () => {
    // The regression: the composer restores its draft in a child effect, which
    // runs BEFORE this layout's effect, so the `inputValue` prop still reads
    // empty when the proposal is considered. Reading the prop alone would
    // overwrite work the user had already started.
    storeFirstBuildPrompt(PROPOSAL);
    writeDraft(null, 'a message I was in the middle of writing');

    render(<Harness showWelcomeMessage />);

    expect(composerValue()).toBe('a message I was in the middle of writing');
  });

  it('proposes nothing outside the home view, and keeps the proposal for it', () => {
    storeFirstBuildPrompt(PROPOSAL);

    render(<Harness showWelcomeMessage={false} />);

    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toContain(PROPOSAL);
  });

  it('does nothing when onboarding proposed nothing', () => {
    render(<Harness showWelcomeMessage />);

    expect(composerValue()).toBe('');
  });

  it('guards the COMPOSER draft slot, not the page one', () => {
    // A CONTRACT test, not a reproduction: ChatPageV2 leaves the composer's
    // `conversationId` unset today, so in production both ids are undefined and
    // resolve to the same slot. This pins the coupling that makes that safe -
    // only the composer's id decides which draft is about to be restored into
    // it, so reading the page's would guard a slot nobody is writing.
    storeFirstBuildPrompt(PROPOSAL);
    writeDraft('composer-conversation', 'the draft actually being restored');

    render(
      <Harness
        showWelcomeMessage
        composerConversationId="composer-conversation"
        pageConversationId="a-different-conversation"
      />,
    );

    expect(composerValue()).toBe('the draft actually being restored');
  });

  it('leaves the proposal alone when the welcome flag is set but a data source is expanded', () => {
    // The other arm that wins over the welcome view while its flag is true.
    storeFirstBuildPrompt(PROPOSAL);
    currentView = { view: 'data', dataSourceId: 'ds-1' };

    render(<Harness showWelcomeMessage enableDataSource />);

    expect(screen.queryAllByTestId('composer')).toHaveLength(0);
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toContain(PROPOSAL);
  });

  it('leaves the proposal alone when the welcome flag is set but a dashboard is on screen', () => {
    // A CONTRACT test: ChatPageV2 never passes `dashboardPath` today, so this
    // arm is unreachable in production. It pins the rule the reachable
    // data-source arm above shares - the welcome view is the LAST arm of a
    // ternary chain, so its flag can be true while no composer renders at all,
    // and consuming the proposal there spends it on nothing while reporting it
    // as delivered.
    storeFirstBuildPrompt(PROPOSAL);

    render(<Harness showWelcomeMessage dashboardPath="/app/board" />);

    expect(screen.queryAllByTestId('composer')).toHaveLength(0);
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toContain(PROPOSAL);
  });
});
