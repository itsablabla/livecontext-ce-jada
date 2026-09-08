import { describe, expect, it } from 'vitest';
import { settingsNavItems } from '../settingsNavItems';
import { Bot, Bug } from 'lucide-react';

describe('settingsNavItems - cloud-link merged into cloud-account', () => {
  const hrefs = settingsNavItems.map((i) => i.href);

  it('exposes the unified "Cloud" entry', () => {
    const cloud = settingsNavItems.find((i) => i.href === '/app/settings/cloud-account');
    expect(cloud).toBeTruthy();
    expect(cloud?.label).toBe('Cloud');
    expect(cloud?.adminOnly).toBe(true);
  });

  it('no longer carries a separate legacy cloud-link entry', () => {
    expect(hrefs).not.toContain('/app/settings/cloud-link');
  });

  it('has exactly one cloud-* settings entry (the fused page)', () => {
    expect(hrefs.filter((h) => h.startsWith('/app/settings/cloud')).length).toBe(1);
  });
});

describe('settingsNavItems - Agents & Chat entry', () => {
  const agents = settingsNavItems.find((i) => i.href === '/app/settings/agents');

  // The label is the string the CE nav-gating e2e looks for (ALWAYS_VISIBLE_LABELS in
  // e2e/ce/ce-settings-nav-gating-ui.spec.ts): renaming it here without renaming it there
  // reddens that spec per-label visibility loop.
  it('exposes the agent & chat defaults page to every user', () => {
    expect(agents).toBeTruthy();
    expect(agents?.label).toBe('Agents & Chat');
    // Self-service defaults, so no admin gate - and CE self-hosters get them too.
    expect(agents?.adminOnly).toBeUndefined();
    expect(agents?.hiddenInCE).toBeUndefined();
    expect(agents?.hidden).toBeUndefined();
  });

  it('carries its own icon rather than reusing the admin Agent Debug one', () => {
    const debug = settingsNavItems.find((i) => i.href === '/app/settings/agent-debug');
    expect(agents?.icon).toBe(Bot);
    expect(debug?.icon).toBe(Bug);
  });

  it('sits in the account group, above the billing separator', () => {
    const hrefs = settingsNavItems.map((i) => i.href);
    expect(hrefs.indexOf('/app/settings/agents'))
      .toBeLessThan(hrefs.indexOf('/app/settings/pricing'));
    // groupStart draws the separator, so claiming it would split the account group in two.
    expect(agents?.groupStart).toBeUndefined();
  });
});
