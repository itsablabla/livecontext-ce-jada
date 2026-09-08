/**
 * The three places the inspector-open-mode preference is only useful if it is wired, and
 * where nothing else can see that it is.
 *
 * <p>Read as SOURCE, deliberately and for two different reasons. `app/workflows/layout.tsx`
 * is an async server component that awaits the request locale and the message catalogue,
 * which a jsdom render cannot provide - the same reason the sibling `layoutBillingModals`
 * suite reads it this way, and what matters here is the mount, which is a fact about the
 * file. `WorkflowBuilder.tsx` is renderable in principle and not in practice: it is the
 * whole builder, and every unit in it is tested through its own hook instead.
 *
 * <p>What that leaves uncovered is the reason this file exists at all: delete the hook call
 * from the builder, or a provider from the route, and every other test in the change still
 * passes while the feature is gone. These assertions are cheap and they are the only thing
 * standing between that edit and a silent regression.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const read = (relative: string) => readFileSync(join(process.cwd(), relative), 'utf8');

const layout = read('app/workflows/layout.tsx');
const builder = read('app/workflows/builder/components/WorkflowBuilder.tsx');
const inspector = read('app/workflows/builder/components/InspectorPanel.tsx');
const settings = read('app/[locale]/app/settings/overview/page.tsx');

/**
 * Lines that are CODE, not prose.
 *
 * <p>The need for this is itself worth stating: the comment explaining why the old call was
 * removed necessarily NAMES that call, so a plain substring scan matched the very
 * explanation of its absence and reported the fix as un-applied. A source test that reads
 * prose as code is worse than no source test.
 */
function codeLines(source: string): string[] {
  return source
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '' && !line.startsWith('//') && !line.startsWith('*') && !line.startsWith('/*'));
}

const hasCodeLine = (source: string, fragment: string): boolean =>
  codeLines(source).some((line) => line.includes(fragment));

describe('the standalone builder route carries the inspector preferences', () => {
  /**
   * `/workflows` is a sibling of `/[locale]/app`, not a child, so nothing mounted under the
   * app layout reaches it. Before this it mounted only the layout-direction provider, so
   * the canvas settings panel here read the safe-hook defaults whatever the user had chosen
   * in Settings, and its inspector selects wrote to a no-op setter.
   *
   * <p>Only ONE of the two is the answer to that, which is what the next test pins: the
   * open mode is wired, and the dock deliberately is not, because this route has no side
   * panel for a docked inspector to live in.
   */
  it('mounts the open-mode provider, without which the preference cannot reach the canvas', () => {
    expect(layout).toContain("from '@/contexts/InspectorOpenModeContext'");
    expect(layout).toContain('<InspectorOpenModeProvider>');
  });

  it('does NOT mount the dock provider, which this route could not honour', () => {
    // The asymmetry is the decision. This route mounts no side panel, so a docked
    // inspector is impossible here and the settings panel hides that control - a provider
    // would carry a preference nothing on the route can read or show. Pinned because the
    // obvious "make it symmetric" edit would add dead weight back.
    expect(layout).not.toContain('<InspectorDockProvider>');
  });

  it('keeps the layout-direction provider it already had', () => {
    // Named one by one rather than counted: the point of this file is that the list must
    // not lose an entry, and a count passes while an entry is swapped out.
    expect(layout).toContain('<WorkflowLayoutDirectionProvider>');
  });
});

describe('the builder applies the preference', () => {
  it('calls the resting-mode hook, which is the only thing that applies it', () => {
    expect(builder).toContain("from '../hooks/useInspectorRestingMode'");
    expect(builder).toContain('useInspectorRestingMode({');
  });

  it('feeds it the selection and the run mode, the two inputs the rule turns on', () => {
    const call = builder.slice(builder.indexOf('useInspectorRestingMode({'));
    const args = call.slice(0, call.indexOf('});'));
    expect(args).toContain('isRunMode');
    expect(args).toContain('hasSelection');
    expect(args).toContain('setIsAdvancedMode');
  });
});

describe('the account settings page describes the DEFAULT, not the open workflow', () => {
  /**
   * It read `direction`, the ACTIVE value, so once a workflow whose plan stamps a direction
   * had been opened in the session, this page reported that workflow's direction as the
   * user's default - and being a controlled select, it could not then be used to re-pick
   * the value it was misreporting, so there was no way to state the default at all.
   *
   * Pinned as source because the two readers are one identifier apart: swap it back and the
   * page still renders, still persists, and every behavioural test in the change stays
   * green while the page lies again.
   */
  it('reads defaultDirection, which no open workflow can move', () => {
    expect(hasCodeLine(settings, 'defaultDirection: workflowLayoutDirection')).toBe(true);
    expect(hasCodeLine(settings, 'direction: workflowLayoutDirection')).toBe(false);
  });
});

describe('a per-node constraint does not write the per-session value', () => {
  /**
   * The defect this pins is invisible in every rendered test of the pieces, because it is
   * about WHICH state a constraint writes. `shouldForceSmallMode` says "this node has no
   * three-column view to show". It used to say it by calling `onAdvancedChange(false)`,
   * which is the BUILDER's flag, shared by every node - so opening one unconfigured node
   * turned the preference off for the rest of the session, and nothing restored it until
   * the selection emptied. It reads as the preference working "sometimes".
   */
  it('no longer pushes the forced-small decision back up to the builder', () => {
    expect(hasCodeLine(inspector, 'onAdvancedChange(false)')).toBe(false);
  });

  it('resolves the effective value locally instead, from the requested one', () => {
    expect(hasCodeLine(inspector, 'const isAdvanced = isAdvancedRequested && !shouldForceSmallMode;')).toBe(true);
  });

  it('points a forced-small node at the one tab it actually mounts', () => {
    // The other half of the same fix. Dropping the shared write also dropped the tab reset
    // that used to ride on it, and a mobile reader who had opened Output then selected a
    // forced-small node got a Tabs root pointing at content that is not mounted: a blank
    // inspector. Derived rather than reset by an effect, so there is no first frame of it.
    expect(hasCodeLine(inspector, "shouldForceSmallMode ? 'parameter' : activeTab")).toBe(true);
    expect(hasCodeLine(inspector, 'activeTab={activeTabForNode}')).toBe(true);
  });

  it('still lets the user toggle the mode, which is a different call', () => {
    // The guard against over-correcting: `onAdvancedChange` is how the inspector's own
    // control changes the mode on purpose. Only the automatic reset was wrong.
    expect(hasCodeLine(inspector, 'onAdvancedChange={onAdvancedChange}')).toBe(true);
  });
});
