/**
 * The side panel must never paint over the menus opened from inside it.
 *
 * A docked panel used to carry no z-index at all, so every portalled overlay was
 * above it for free. Detaching gave it `z-[61]` and full screen keeps it there,
 * and BOTH render a tab bar whose Add-tab picker and tab context menu are
 * `PopoverContent` - portalled to document.body, competing with the whole page
 * rather than with their trigger's container. At the stock `z-50` those menus
 * open behind an opaque full-viewport panel: no error, nothing to click, and no
 * component test can see it because jsdom loads no stylesheet.
 *
 * So the invariant is pinned at the source instead: whatever tier the panel's
 * own CONTAINER paints at, the popover layer sits above it. This fails the day
 * someone bumps the panel and not the overlay, which is exactly how it got in.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { fileURLToPath } from 'url';

// Resolved from THIS file, not from the working directory: the test should not
// depend on having been launched from `frontend/`.
const FRONTEND = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const read = (rel: string) => readFileSync(resolve(FRONTEND, rel), 'utf8');

/**
 * A file with its comments removed.
 *
 * Both files explain their tier by naming the neighbours they have to clear, so
 * a raw scan reads the prose as code and compares a class against a number in a
 * sentence.
 */
const code = (source: string) => source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1');

/**
 * Every z-index class a file actually emits.
 *
 * Both spellings: Tailwind's own scale (`z-50`) and an arbitrary value
 * (`z-[64]`). Matching only the bracketed form made a regression to the stock
 * `z-50` read as "this file declares no z-index" - a failure for the wrong
 * reason, and a pass the moment the comparison was written the other way. The
 * bare form needs a lookahead rather than a trailing `\b`, which can never
 * match after `]`.
 */
function zIndexes(source: string): number[] {
  return [...code(source).matchAll(/\bz-(?:\[(\d+)\]|(\d+)(?![\w-]))/g)]
    .map((m) => Number(m[1] ?? m[2]));
}

/**
 * The tiers the panel's own container paints at.
 *
 * The pointer-capture sheet laid over the page for the length of a float drag
 * is excluded, and only that one: it exists solely while a pointer is held down
 * on a resize grip, when no menu can be open, so it is allowed above the
 * popover layer. It is removed by POSITION rather than by value, because
 * filtering on the number would also drop a persistent tier that happened to
 * share it and silently shrink what the comparison covers.
 */
function persistentPanelTiers(): number[] {
  const source = code(read('components/app/SidePanel.tsx'));
  const overlay = /data-side-panel-drag-overlay[\s\S]{0,200}?z-\[(\d+)\]/.exec(source);
  expect(overlay, 'the float-drag overlay moved or lost its z-index').not.toBeNull();
  const transientAt = overlay!.index + overlay![0].lastIndexOf('z-[');
  return [...source.matchAll(/\bz-(?:\[(\d+)\]|(\d+)(?![\w-]))/g)]
    .filter((m) => m.index !== transientAt)
    .map((m) => Number(m[1] ?? m[2]));
}

/** The lowest tier a popover can paint at, i.e. the one that has to clear the panel. */
function popoverFloor(): number {
  const found = zIndexes(read('components/ui/popover.tsx'));
  expect(found.length, 'popover.tsx declares no z-index').toBeGreaterThan(0);
  return Math.min(...found);
}

describe('side panel vs the overlays opened from inside it', () => {
  it('keeps every popover above every tier the panel container paints at', () => {
    const panelZ = persistentPanelTiers();

    expect(panelZ.length).toBeGreaterThan(0);
    expect(popoverFloor()).toBeGreaterThan(Math.max(...panelZ));
  });

  it('keeps the popover layer below the app modal tier, which must still cover it', () => {
    // A confirmation dialog opened from a popover has to paint over it, as it
    // always did. `z-[100]` is the floor of the app's modal family
    // (AgentErrorModal, CeCloudCreditModal, MissingApiKeyModal, ImageLightbox),
    // and raising popovers past it to clear PanelResizeHandle - see below -
    // would put every menu over those instead.
    expect(popoverFloor()).toBeLessThan(100);
  });

  it('does NOT claim to cover PanelResizeHandle, which is a documented exception', () => {
    // The docked panel's edge handle is a separate `fixed` strip at z-[100], so
    // a popover reaching the panel's edge has a narrow band where the handle
    // wins. That predates this change (it was above z-50 too) and cannot be
    // fixed by raising the popover without crossing the modal tier above, so it
    // is stated here rather than left to look like an oversight. Asserted so
    // the day the handle drops below the popover, this note is removed.
    const handleZ = zIndexes(read('components/ui/PanelResizeHandle.tsx'));

    expect(Math.max(...handleZ)).toBeGreaterThan(popoverFloor());
  });

  it('keeps tooltips above popovers, so a hint over a menu is still readable', () => {
    expect(Math.max(...zIndexes(read('components/ui/tooltip.tsx')))).toBeGreaterThan(popoverFloor());
  });
});
