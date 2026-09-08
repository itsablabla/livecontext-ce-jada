// @vitest-environment jsdom
/**
 * AgentChatDefaults is the single editor behind the two new agent-settings surfaces:
 * the Agents page "Settings" tab (/app/agent?view=settings) and Settings > Agents & Chat
 * (/app/settings/agents). Both must edit the per-(user, workspace) chat defaults -
 * i.e. mount ChatConfigPanel with `userDefault`, NOT a conversation- or agent-scoped
 * panel - which is what makes the general chat configurable from either place.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

const panelProps: Record<string, unknown>[] = [];
vi.mock('@/components/chat/ChatConfigPanel', () => ({
  ChatConfigPanel: (props: Record<string, unknown>) => {
    panelProps.push(props);
    return <div data-testid="chat-config-panel" />;
  },
}));
// Returns the FULLY qualified key so the assertions below pin the namespace too: a
// header wired to the wrong namespace still renders, just with English fallbacks.
vi.mock('next-intl', () => ({
  useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

import { AgentChatDefaults } from '../AgentChatDefaults';

afterEach(() => {
  panelProps.length = 0;
  cleanup();
});

describe('AgentChatDefaults', () => {
  it('edits the per-(user, workspace) defaults (userDefault target)', () => {
    render(<AgentChatDefaults />);
    expect(screen.getByTestId('chat-config-panel')).toBeTruthy();
    expect(panelProps).toHaveLength(1);
    expect(panelProps[0].userDefault).toBe(true);
    // A conversation or agent id would silently scope the edits to one chat instead.
    expect(panelProps[0].agentId).toBeUndefined();
    expect(panelProps[0].conversationId).toBeUndefined();
  });

  it('titles itself from settings.agentDefaults so both hosts show the same heading', () => {
    render(<AgentChatDefaults />);
    expect(screen.getByText('settings.agentDefaults.title')).toBeTruthy();
    expect(screen.getByText('settings.agentDefaults.subtitle')).toBeTruthy();
  });

  // The settings PAGE owns its title; the Agents-page tab does not - its sibling tabs render
  // an h2 at most, so an h1 there would make the page heading level come and go with the tab.
  it('titles the settings page with an h1 and the Agents tab with an h2', () => {
    const page = render(<AgentChatDefaults />);
    expect(page.container.querySelector('h1')?.textContent).toBe('settings.agentDefaults.title');
    cleanup();

    const tab = render(<AgentChatDefaults headingLevel="h2" />);
    expect(tab.container.querySelector('h1')).toBeNull();
    expect(tab.container.querySelector('h2')?.textContent).toBe('settings.agentDefaults.title');
  });

  // The Agents page column is max-w-6xl; uncapped, the sliders and the 2-column number
  // grids of the panel stretch across the whole width and become hard to read.
  it('caps its width like the same editor already is in Settings > Preferences', () => {
    const { container } = render(<AgentChatDefaults />);
    expect(container.querySelector('.max-w-4xl')).toBeTruthy();
  });
});
