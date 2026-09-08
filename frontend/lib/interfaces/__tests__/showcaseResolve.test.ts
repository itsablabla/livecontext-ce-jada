import { describe, expect, it } from 'vitest';
import { resolveShowcaseContent } from '../showcaseResolve';

/**
 * Each case here is a rendering that LOOKS fine and is wrong: the wrong mode
 * rewrites live placeholders to `[var]`, and passing an empty data object makes
 * the thumbnail claim it has content it does not have. Neither throws, so only
 * these assertions catch a regression.
 */
describe('resolveShowcaseContent', () => {
  it('uses backend-resolved HTML verbatim, in run mode', () => {
    const result = resolveShowcaseContent([{ data: { _resolvedHtml: '<p>done</p>' } }]);

    expect(result.effectiveHtml).toBe('<p>done</p>');
    // Edit mode would rewrite the `{{var|default}}` the snapshot deliberately kept.
    expect(result.mode).toBe('run');
    expect(result.resolvedData).toBeUndefined();
  });

  it('passes item data through as resolved data, in run mode', () => {
    const result = resolveShowcaseContent([{ data: { title: 'Volcano' } }]);

    expect(result.resolvedData).toMatchObject({ title: 'Volcano' });
    expect(result.mode).toBe('run');
    expect(result.effectiveHtml).toBeUndefined();
  });

  it('falls back to edit mode when the item carries no data', () => {
    const result = resolveShowcaseContent([{ data: {} }]);

    // An empty object is not content: passing it would render a run-mode view
    // of an interface whose every placeholder resolves to nothing.
    expect(result.resolvedData).toBeUndefined();
    expect(result.mode).toBe('edit');
  });

  it('merges trigger data under the flattened keys a template addresses', () => {
    const result = resolveShowcaseContent(
      [{ data: { title: 'Volcano' } }],
      { 'trigger:new_theme': { theme: 'volcano' } },
    );

    // A template writes `{{trigger:new_theme.output.theme}}`, so the merge
    // flattens to that exact key (and its shorthand), not to a nested object.
    expect(result.resolvedData?.['trigger:new_theme.output.theme']).toBe('volcano');
    expect(result.resolvedData?.['trigger:new_theme.theme']).toBe('volcano');
    expect(result.resolvedData?.title).toBe('Volcano');
  });

  it('shows the first item, which is the newest epoch', () => {
    const result = resolveShowcaseContent([
      { data: { _resolvedHtml: '<p>newest</p>' } },
      { data: { _resolvedHtml: '<p>older</p>' } },
    ]);

    expect(result.effectiveHtml).toBe('<p>newest</p>');
  });

  it('handles a render with no items at all', () => {
    expect(resolveShowcaseContent([]).mode).toBe('edit');
    expect(resolveShowcaseContent(undefined).mode).toBe('edit');
    expect(resolveShowcaseContent(null).effectiveHtml).toBeUndefined();
  });

  it('handles an item whose data is null', () => {
    const result = resolveShowcaseContent([{ data: null }]);

    expect(result.mode).toBe('edit');
    expect(result.resolvedData).toBeUndefined();
  });
});
