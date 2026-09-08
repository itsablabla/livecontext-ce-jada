// @vitest-environment jsdom
/**
 * What the composer's button row does when it runs out of width.
 *
 * <p>The row is one grid: the leading controls (attach, tools & skills)
 * generate) on the left, the model selector, the mic and the send button on the
 * right. The bubble around it is `overflow-hidden`, so when the row no longer
 * fits, nothing wraps and nothing scrolls - the RIGHT end is simply cut off, and
 * the right end is the send/stop button. In a side panel dragged to its 320px
 * minimum, 82px of that button was missing, with nothing on screen saying why.
 *
 * <p>The fix is to merge those leading controls into one button opening a
 * menu below a measured width. This pins that behaviour: which controls are
 * offered on each side of the line, that the same three actions are reachable
 * either way, that the measurement follows the ELEMENT rather than the viewport
 * (the composer is routinely narrow on a wide screen), and that a renderer with
 * no ResizeObserver still draws the row it always drew.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: {} }));
vi.mock('@/components/chat/AttachmentHandler', () => ({
  AttachmentHandler: ({ isOpen }: { isOpen: boolean }) =>
    isOpen ? <div data-testid="tools-panel" /> : null,
}));
vi.mock('@/components/chat/QueuedMessageBar', () => ({ QueuedMessageBar: () => null }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: {
    getModels: vi.fn().mockResolvedValue({
      models: [{ model: 'seedance-2.0', kind: 'video' }],
      count: 1,
      kinds: ['video'],
    }),
  },
}));
// next-intl's navigation module cannot resolve 'next/navigation' under vitest, so the composer's
// locale-aware router is stood in for. This suite is about layout, not about where it goes.
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));

import { MessageComposer } from '../MessageComposer';

/**
 * jsdom does not lay anything out, so both halves of the measurement are stood
 * in for: the width every element reports, and the ResizeObserver that tells the
 * composer to read it again. `resizeTo` changes the width and fires the
 * observers, which is exactly what dragging a side panel does.
 */
const layout = vi.hoisted(() => ({
  width: 0,
  observers: new Set<() => void>(),
}));

function resizeTo(width: number) {
  layout.width = width;
  act(() => { layout.observers.forEach((notify) => notify()); });
}

beforeEach(() => {
  layout.width = 800;
  layout.observers.clear();
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
    () => ({ width: layout.width, height: 0, top: 0, left: 0, right: 0, bottom: 0, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect,
  );
  // Entries are handed to the callback because the composer is not the only
  // thing observing: an open Radix popover positions itself off its own
  // ResizeObserver and reads `entries[0]`, so a callback invoked bare would
  // throw inside the popover rather than fail an assertion here.
  class StubResizeObserver {
    private readonly targets = new Set<Element>();
    private readonly notify: () => void;
    constructor(cb: ResizeObserverCallback) {
      this.notify = () => {
        const entries = Array.from(this.targets).map((target) => ({
          target,
          contentRect: target.getBoundingClientRect(),
        })) as unknown as ResizeObserverEntry[];
        cb(entries, this as unknown as ResizeObserver);
      };
    }
    observe(target: Element) { this.targets.add(target); layout.observers.add(this.notify); }
    unobserve(target: Element) { this.targets.delete(target); }
    disconnect() { this.targets.clear(); layout.observers.delete(this.notify); }
  }
  vi.stubGlobal('ResizeObserver', StubResizeObserver);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function renderComposer(width: number, minimal = false) {
  layout.width = width;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MessageComposer
        minimal={minimal}
        inputValue=""
        onInputChange={() => {}}
        onSendMessage={() => {}}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => {}}
      />
    </QueryClientProvider>,
  );
}

const moreButton = () => screen.queryByLabelText('chat.moreActions');
const attachButton = () => screen.queryByTitle('chat.attachFiles');
const toolsButton = () => screen.queryByTitle('credentials.toolsAndSkills');
const sendButton = () => screen.getByTitle('chat.send');

/** Settle the generation catalogue query so a render is never read mid-flight. */
async function settle() {
  await act(async () => { await Promise.resolve(); });
}

describe('MessageComposer - the button row when width runs out', () => {
  it('a wide composer keeps the leading controls side by side', async () => {
    renderComposer(800);
    await settle();

    expect(attachButton()).toBeInTheDocument();
    expect(toolsButton()).toBeInTheDocument();
    // No menu offered while there is room: an overflow control that is always
    // there is just a fourth button competing for the width it was meant to save.
    expect(moreButton()).not.toBeInTheDocument();
  });

  it('a narrow composer replaces them with ONE button, so the send button keeps its place', async () => {
    renderComposer(320);
    await settle();

    expect(moreButton()).toBeInTheDocument();
    // The point of the merge: three controls' worth of width handed back. If any
    // of them were still drawn in the row, the row would be exactly as cramped.
    expect(attachButton()).not.toBeInTheDocument();
    expect(toolsButton()).not.toBeInTheDocument();
    // Still the row's last control, and still a send button.
    expect(sendButton()).toBeInTheDocument();
  });

  it('the merged menu offers the same actions the row was showing', async () => {
    renderComposer(320);
    await settle();

    fireEvent.click(moreButton()!);

    expect(await screen.findByText('chat.attachFiles')).toBeInTheDocument();
    expect(screen.getByText('credentials.toolsAndSkills')).toBeInTheDocument();
  });

  it('Tools & Skills opens the same panel from the menu as from the button', async () => {
    renderComposer(320);
    await settle();

    fireEvent.click(moreButton()!);
    fireEvent.click(await screen.findByText('credentials.toolsAndSkills'));

    // The action itself, not merely a menu row that closes: the merged row has
    // to DO what the button did, or it is a narrower composer with fewer features.
    await waitFor(() => expect(screen.getByTestId('tools-panel')).toBeInTheDocument());
  });

  it('a DM drops Tools & Skills from the menu, exactly as it drops the button', async () => {
    // `minimal` hides the AI-only controls. The merge must not smuggle one back
    // in through the menu.
    renderComposer(320, true);
    await settle();

    fireEvent.click(moreButton()!);

    expect(await screen.findByText('chat.attachFiles')).toBeInTheDocument();
    expect(screen.queryByText('credentials.toolsAndSkills')).not.toBeInTheDocument();
  });

  it('follows the composer element, not the viewport: dragging a panel narrower merges the row', async () => {
    // The composer lives inside a resizable side panel and inside the builder's
    // trigger panels, so it is routinely narrow on a wide screen. A viewport
    // breakpoint would leave every one of those cases clipped.
    renderComposer(800);
    await settle();
    expect(moreButton()).not.toBeInTheDocument();

    resizeTo(320);
    expect(moreButton()).toBeInTheDocument();
    expect(attachButton()).not.toBeInTheDocument();

    resizeTo(800);
    expect(moreButton()).not.toBeInTheDocument();
    expect(attachButton()).toBeInTheDocument();
  });

  it('closes the menu when the composer widens under it', async () => {
    // The trigger it is anchored to stops being rendered, so an open menu would
    // hang in mid-air pointing at nothing.
    renderComposer(320);
    await settle();
    fireEvent.click(moreButton()!);
    expect(await screen.findByText('chat.attachFiles')).toBeInTheDocument();

    resizeTo(800);

    await waitFor(() => expect(screen.queryByText('chat.attachFiles')).not.toBeInTheDocument());
  });

  it('the file picker survives the switch, so a pick in flight is not dropped', async () => {
    const { container } = renderComposer(800);
    await settle();
    const picker = container.querySelector('input[type="file"]');
    expect(picker).toBeTruthy();

    resizeTo(320);

    // The SAME node: remounting the input mid-pick would throw away the dialog
    // the user is standing in front of.
    expect(container.querySelector('input[type="file"]')).toBe(picker);
  });

  it('draws the full row where nothing can measure it', async () => {
    // A server render, and jsdom without the stub above. Falling back to the
    // merged row there would hide two controls on every wide screen for the
    // first paint; falling back to the full row is what the composer always did.
    vi.unstubAllGlobals();
    // Removing the global IS the condition under test.
    delete (globalThis as { ResizeObserver?: unknown }).ResizeObserver;

    renderComposer(320);
    await settle();

    expect(attachButton()).toBeInTheDocument();
    expect(moreButton()).not.toBeInTheDocument();
  });
});
