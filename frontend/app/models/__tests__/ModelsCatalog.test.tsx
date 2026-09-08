// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import { readFileSync } from 'fs';
import path from 'path';

import ModelsCatalog from '../_components/ModelsCatalog';
import { CATALOG_MODELS, MODEL_CATALOG_STATS, type ModelCapability } from '../_components/modelsData';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import { formatPrice, formatTokens, formatYear } from '../_components/modelsFormat';

// The provider filter navigates (the URL is the shareable state), so the router is
// what these tests observe instead of window.location: jsdom has no Next router.
const replace = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: (...a: unknown[]) => replace(...a) }) }));

const FRONTEND = path.resolve(__dirname, '../../../');
const REPO = path.resolve(FRONTEND, '..');

beforeAll(() => {
  // jsdom has no layout, so neither of these exists. The component calls
  // scrollIntoView when a timeline chip selects a row, and reads scrollWidth to
  // pin the strip to the present.
  Element.prototype.scrollIntoView = () => {};
});

afterEach(cleanup);

describe('CATALOG_MODELS dataset', () => {
  it('carries a well-formed release date for every model', () => {
    for (const model of CATALOG_MODELS) {
      expect(model.released, model.id).toMatch(/^\d{4}-(0[1-9]|1[0-2])(-(0[1-9]|[12]\d|3[01]))?$/);
    }
  });

  it('is ordered newest first, which the default sort relies on', () => {
    // The component does NOT re-sort for 'newest' - it renders the array order -
    // so a generator that stopped sorting would silently scramble the list.
    const dates = CATALOG_MODELS.map((m) => m.released);
    expect([...dates].sort().reverse()).toEqual(dates);
  });

  it('has no duplicate model id', () => {
    const ids = CATALOG_MODELS.map((m) => m.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('only names providers that have an icon, so no row renders iconless', () => {
    for (const model of CATALOG_MODELS) {
      expect(PROVIDER_ICON_MAP, model.id).toHaveProperty(model.provider);
    }
  });

  it('carries positive numbers everywhere a number is displayed', () => {
    for (const model of CATALOG_MODELS) {
      expect(model.context, model.id).toBeGreaterThan(0);
      expect(model.priceIn, model.id).toBeGreaterThan(0);
      expect(model.priceOut, model.id).toBeGreaterThan(0);
    }
  });

  it('only uses declared capabilities', () => {
    const allowed: ModelCapability[] = ['reasoning', 'vision', 'tools', 'caching', 'web'];
    for (const model of CATALOG_MODELS) {
      for (const capability of model.caps) expect(allowed, model.id).toContain(capability);
    }
  });

  it('reports catalog counts that describe the catalog, not the page', () => {
    // These are the CATALOG totals. The page's stat tiles must never present them
    // as what it lists: they used to say "203 models / 14 providers" above a list
    // of 91 from 12, so a visitor filtering by provider found two chips fewer than
    // the tile promised and could never reach model 92.
    expect(MODEL_CATALOG_STATS.distinctModels).toBeGreaterThanOrEqual(CATALOG_MODELS.length);
    expect(MODEL_CATALOG_STATS.catalogModels).toBeGreaterThan(MODEL_CATALOG_STATS.aggregatorModels);

    // The tiles live in the CATALOGUE component, not the page, since they have to
    // follow the provider filter. They must derive from the FILTERED set: reading
    // the catalogue totals here is the original bug, and reading the full dataset
    // is the same bug one filter later.
    const src = readFileSync(path.resolve(FRONTEND, 'app/models/_components/ModelsCatalog.tsx'), 'utf8');
    const start = src.indexOf('const stats = useMemo(');
    expect(start, 'the stat tiles moved again; retarget this guard').toBeGreaterThan(-1);
    const tileBlock = src.slice(start, src.indexOf('}, [filtered, provider]);', start));
    expect(tileBlock).not.toMatch(/MODEL_CATALOG_STATS/);
    expect(tileBlock).not.toMatch(/CATALOG_MODELS/);
    expect(tileBlock).toMatch(/filtered\.length/);
  });
});

describe('modelsData.ts stays in sync with its curation source', () => {
  // The .ts file is GENERATED from scripts/models/models-page.json. Editing
  // one without the other is the failure this catches, offline, in CI.
  const curation = JSON.parse(
    readFileSync(path.resolve(REPO, 'scripts/models/models-page.json'), 'utf8'),
  ) as { models: { id: string; provider: string; name: string; released: string }[] };

  it('lists exactly the curated models', () => {
    expect(new Set(CATALOG_MODELS.map((m) => m.id))).toEqual(new Set(curation.models.map((m) => m.id)));
  });

  it('keeps every curated release date, provider and name', () => {
    const generated = new Map(CATALOG_MODELS.map((m) => [m.id, m]));
    for (const curated of curation.models) {
      const model = generated.get(curated.id);
      expect(model, curated.id).toBeDefined();
      expect(model!.released, curated.id).toBe(curated.released);
      expect(model!.provider, curated.id).toBe(curated.provider);
      expect(model!.name, curated.id).toBe(curated.name);
    }
  });

  it('is marked generated so nobody hand-edits it', () => {
    const src = readFileSync(path.resolve(FRONTEND, 'app/models/_components/modelsData.ts'), 'utf8');
    expect(src.startsWith('// GENERATED by scripts/models/build_models_page.py')).toBe(true);
  });
});

describe('ModelsCatalog formatting follows the app, never the browser', () => {
  // Comments are stripped first: this file's own prose explains what it forbids,
  // and a guard that its own explanation trips is a guard nobody can maintain.
  const src = readFileSync(path.resolve(FRONTEND, 'app/models/_components/ModelsCatalog.tsx'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');

  it('never formats a date or a number through the browser locale', () => {
    // `toLocaleDateString()` / `toLocaleString()` with no locale follow the
    // BROWSER language, so a French visitor would read French months on a page
    // whose copy is English. Hardcoding a locale is banned too.
    expect(src).not.toMatch(/toLocaleDateString/);
    expect(src).not.toMatch(/toLocaleString/);
    expect(src).not.toMatch(/navigator\.language/);
    expect(src).not.toMatch(/'(en|fr|de|es|pt|zh)-[A-Z]{2}'/);
  });
});

describe('ModelsCatalog', () => {
  it('renders the newest models first, capped until the visitor asks for more', () => {
    render(<ModelsCatalog />);
    const newest = CATALOG_MODELS[0];
    expect(screen.getAllByText(newest.name).length).toBeGreaterThan(0);
    // The 21st model is past the collapsed cap, so its id is absent from the list.
    const beyondCap = CATALOG_MODELS[24];
    expect(screen.queryByText(beyondCap.id)).not.toBeInTheDocument();
  });

  it('reveals the WHOLE list on "Show all", not just more of it', () => {
    render(<ModelsCatalog />);
    expect(screen.getAllByRole('listitem')).toHaveLength(20);

    fireEvent.click(screen.getByRole('button', { name: `Show all ${CATALOG_MODELS.length}` }));
    expect(screen.getAllByRole('listitem')).toHaveLength(CATALOG_MODELS.length);
    expect(screen.getByText(CATALOG_MODELS[CATALOG_MODELS.length - 1].id)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show less' }));
    expect(screen.getAllByRole('listitem')).toHaveLength(20);
  });

  it('filters the list and the timeline from the search field', () => {
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: 'kimi' } });

    const kimis = CATALOG_MODELS.filter((m) => m.id.includes('kimi'));
    expect(kimis.length).toBeGreaterThan(1);
    expect(screen.getByText(`${kimis.length} of ${CATALOG_MODELS.length} models listed here.`, { exact: false }))
      .toBeInTheDocument();

    // The timeline is redrawn from the same filtered set: only Kimi chips remain.
    const timeline = screen.getByRole('group', { name: 'Model releases over time' });
    const chips = within(timeline).getAllByRole('button');
    expect(chips).toHaveLength(kimis.length);
  });

  it('matches on the provider name as well as the model name and id', () => {
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: 'deepseek' } });
    const expected = CATALOG_MODELS.filter(
      (m) => m.provider === 'deepseek' || m.id.includes('deepseek') || m.name.toLowerCase().includes('deepseek'),
    );
    expect(screen.getByText(`${expected.length} of ${CATALOG_MODELS.length} models listed here.`, { exact: false }))
      .toBeInTheDocument();
  });

  it('narrows to one provider from its chip, and back on a second click', () => {
    render(<ModelsCatalog />);
    const anthropicCount = CATALOG_MODELS.filter((m) => m.provider === 'anthropic').length;
    const chip = screen.getByRole('button', { name: (name) => name.trim().startsWith('Anthropic') });

    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    const timeline = screen.getByRole('group', { name: 'Model releases over time' });
    expect(within(timeline).getAllByRole('button')).toHaveLength(anthropicCount);

    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'false');
  });

  it('sorts by biggest context and by cheapest input price', () => {
    render(<ModelsCatalog />);
    const rows = () => screen.getAllByRole('listitem');

    fireEvent.click(screen.getByRole('button', { name: 'Biggest context' }));
    const widest = [...CATALOG_MODELS].sort((a, b) => b.context - a.context)[0];
    expect(within(rows()[0]).getByText(widest.id)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Cheapest' }));
    const cheapest = [...CATALOG_MODELS].sort((a, b) => a.priceIn - b.priceIn)[0];
    expect(within(rows()[0]).getByText(cheapest.id)).toBeInTheDocument();
  });

  it('offers a way out when nothing matches', () => {
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: 'zzzz' } });
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Clear filters/ }));
    expect(screen.getAllByRole('listitem').length).toBeGreaterThan(0);
  });

  it('formats context windows and prices without a locale', () => {
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: 'claude-haiku-4-5' } });
    const row = screen.getAllByRole('listitem')[0];
    expect(within(row).getByText('200K')).toBeInTheDocument();
    expect(within(row).getByText('$1')).toBeInTheDocument();
    expect(within(row).getByText('$5')).toBeInTheDocument();
    expect(within(row).getByText('Oct 15, 2025')).toBeInTheDocument();
  });

  it('renders a month-only announcement without inventing a day', () => {
    const monthOnly = CATALOG_MODELS.find((m) => m.released.length === 7);
    expect(monthOnly).toBeDefined();
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: monthOnly!.id } });
    const row = screen.getAllByRole('listitem')[0];
    const [year, month] = monthOnly!.released.split('-');
    const names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    expect(within(row).getByText(`${names[Number(month) - 1]} ${year}`)).toBeInTheDocument();
  });

  it('names the provider chip too when it is part of why nothing matched', () => {
    render(<ModelsCatalog />);
    const anthropic = screen.getByRole('button', { name: (name) => name.trim().startsWith('Anthropic') });
    fireEvent.click(anthropic);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: 'qwen' } });

    // Blaming the text query alone would send the visitor hunting for a typo that
    // is not there: the provider chip is half the reason the list is empty.
    const empty = screen.getByText(/No model matches/);
    expect(empty).toHaveTextContent('qwen');
    expect(empty).toHaveTextContent('Anthropic');
  });

  it('puts the max output tokens where the context window is explained', () => {
    // maxOutput is generated for every model; before this it was displayed nowhere.
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: 'claude-haiku-4-5' } });
    const haiku = CATALOG_MODELS.find((m) => m.id === 'claude-haiku-4-5')!;
    const cell = within(screen.getAllByRole('listitem')[0]).getByText('200K');
    expect(cell).toHaveAttribute('title', expect.stringContaining('200,000 tokens in'));
    expect(cell).toHaveAttribute('title', expect.stringContaining(String(haiku.maxOutput! / 1000)));
  });

  it('re-pins the timeline on the months it draws, not on every keystroke', () => {
    // The effect used to depend on the columns ARRAY, rebuilt on each render, so a
    // visitor who had scrolled back into 2024 was snapped to the present on every
    // letter typed. Keying it on the month list is what makes that stop.
    const src = readFileSync(path.resolve(FRONTEND, 'app/models/_components/ModelsCatalog.tsx'), 'utf8');
    expect(src).toMatch(/const columnsKey = columns\.map/);
    expect(src).toMatch(/\}, \[columnsKey\]\);/);
    expect(src).not.toMatch(/\}, \[columns\]\);/);
  });

  it('adds no inert tab stop in front of the timeline chips', () => {
    // The chips are focusable buttons and Tab scrolls the strip to reach them, so
    // a tabindex on the wrapper was one extra stop that did nothing.
    render(<ModelsCatalog />);
    expect(screen.getByRole('group', { name: 'Model releases over time' })).not.toHaveAttribute('tabindex');
  });

  it('drops the selection when the filter changes', () => {
    // A selected row scrolls itself into view when it mounts. If the selection
    // outlived the filter, clearing the search later would re-mount that row and
    // yank the page back to it, from a click the visitor never made.
    render(<ModelsCatalog />);
    const search = screen.getByRole('searchbox', { name: 'Search models' });
    fireEvent.change(search, { target: { value: 'claude-opus-5' } });

    const timeline = screen.getByRole('group', { name: 'Model releases over time' });
    fireEvent.click(within(timeline).getAllByRole('button')[0]);
    expect(within(timeline).getAllByRole('button')[0]).toHaveAttribute('aria-pressed', 'true');

    fireEvent.change(search, { target: { value: 'claude' } });
    const chips = within(screen.getByRole('group', { name: 'Model releases over time' })).getAllByRole('button');
    expect(chips.some((chip) => chip.getAttribute('aria-pressed') === 'true')).toBe(false);
  });

  it('keeps a visible keyboard focus indicator on the search field', () => {
    // `outline-none` here would leave the field with no focus affordance at all:
    // its border is painted inline, so a `focus:border-*` utility cannot win.
    const src = readFileSync(path.resolve(FRONTEND, 'app/models/_components/ModelsCatalog.tsx'), 'utf8');
    expect(src).not.toMatch(/outline-none/);
    const styles = readFileSync(path.resolve(FRONTEND, 'app/models/_components/modelsStyles.ts'), 'utf8');
    expect(styles).toMatch(/\.models-search:focus-visible/);
  });

  it('reveals and marks the model a timeline chip points at', () => {
    render(<ModelsCatalog />);
    // Pick a model past the collapsed cap so the click has to expand the list.
    const deep = CATALOG_MODELS[40];
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), { target: { value: deep.id } });
    const timeline = screen.getByRole('group', { name: 'Model releases over time' });
    const chip = within(timeline).getAllByRole('button')[0];

    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'false');
  });
});

/** The value shown above a stat tile's label. */
function tileValue(label: string): string {
  const labelEl = screen.getByText(label);
  return within(labelEl.parentElement as HTMLElement).getAllByText(/.+/)[0].textContent ?? '';
}

const XAI = CATALOG_MODELS.filter((m) => m.provider === 'xai');

describe('ModelsCatalog opened on a provider', () => {
  it('renders that provider filtered on the FIRST paint, with no interaction', () => {
    // The footer links here with ?provider=. The server resolves it and passes it
    // in, so the narrowed list is in the markup rather than appearing on hydration.
    render(<ModelsCatalog initialProvider="xai" />);
    const rows = within(screen.getByRole('list', { name: 'Models' })).getAllByRole('listitem');
    expect(rows).toHaveLength(XAI.length);
    for (const row of rows) expect(row).toHaveTextContent(/grok/i);
  });

  it("marks that provider's chip as the active one", () => {
    render(<ModelsCatalog initialProvider="xai" />);
    expect(screen.getByRole('button', { name: /xAI/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /^All/ })).toHaveAttribute('aria-pressed', 'false');
  });

  it('lists everything when opened with no provider', () => {
    render(<ModelsCatalog />);
    expect(screen.getByRole('button', { name: /^All/ })).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('ModelsCatalog stat tiles follow the filter', () => {
  it('counts the whole page when nothing is filtered', () => {
    render(<ModelsCatalog />);
    expect(tileValue('models on this page')).toBe(String(CATALOG_MODELS.length));
  });

  it('counts only the provider being shown, never the whole catalogue', () => {
    // The tiles sit above the list, so a tile saying 91 over 11 visible rows is a
    // page contradicting itself. This is the assertion that keeps them honest.
    render(<ModelsCatalog initialProvider="xai" />);
    expect(tileValue('models on this page')).toBe(String(XAI.length));
  });

  it('re-counts when the visitor changes the filter, not just when the page opens', () => {
    render(<ModelsCatalog />);
    fireEvent.click(screen.getByRole('button', { name: /xAI/ }));
    expect(tileValue('models on this page')).toBe(String(XAI.length));
    fireEvent.click(screen.getByRole('button', { name: /^All/ }));
    expect(tileValue('models on this page')).toBe(String(CATALOG_MODELS.length));
  });

  it('reports the cheapest input and widest context OF THAT PROVIDER', () => {
    render(<ModelsCatalog initialProvider="xai" />);
    const cheapest = Math.min(...XAI.map((m) => m.priceIn));
    const widest = Math.max(...XAI.map((m) => m.context));
    expect(tileValue('cheapest input, per 1M')).toBe(formatPrice(cheapest));
    expect(tileValue('widest context window')).toBe(formatTokens(widest));
  });

  it('reports the oldest release by date, not by list position', () => {
    // Reading the last row would describe another model the day the array stops
    // being newest-first, and nothing would report it.
    render(<ModelsCatalog initialProvider="xai" />);
    const oldest = XAI.map((m) => m.released).sort()[0];
    expect(tileValue('oldest release listed')).toBe(formatYear(oldest));
  });

  it('drops the providers tile while one provider is selected', () => {
    // "1 providers represented" is a tile that tells nobody anything.
    render(<ModelsCatalog />);
    expect(screen.getByText('providers represented')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /xAI/ }));
    expect(screen.queryByText('providers represented')).not.toBeInTheDocument();
  });

  it('shows no tiles at all when nothing matches, rather than zeroes', () => {
    render(<ModelsCatalog />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), {
      target: { value: 'zzzznotamodel' },
    });
    expect(screen.queryByText('models on this page')).not.toBeInTheDocument();
    expect(screen.getByText(/No model matches/)).toBeInTheDocument();
  });
});

describe('ModelsCatalog keeps the URL in step with the filter', () => {
  beforeEach(() => replace.mockClear());

  it('navigates to the provider URL when a chip is picked', () => {
    render(<ModelsCatalog />);
    fireEvent.click(screen.getByRole('button', { name: /xAI/ }));
    expect(replace).toHaveBeenCalledWith('/models?provider=xai', { scroll: false });
  });

  it('navigates back to the bare page when the filter is dropped', () => {
    // Otherwise a refresh, or a shared link, restores a filter the visitor dropped.
    render(<ModelsCatalog initialProvider="xai" />);
    fireEvent.click(screen.getByRole('button', { name: /^All/ }));
    expect(replace).toHaveBeenCalledWith('/models', { scroll: false });
  });

  it('does it from "Clear filters" too, not only from the All chip', () => {
    render(<ModelsCatalog initialProvider="xai" />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search models' }), {
      target: { value: 'zzzznotamodel' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Clear filters/ }));
    expect(replace).toHaveBeenCalledWith('/models', { scroll: false });
  });

  it('replaces rather than pushes, so twelve chips do not bury the back button', () => {
    const src = readFileSync(path.resolve(FRONTEND, 'app/models/_components/ModelsCatalog.tsx'), 'utf8');
    expect(src).toMatch(/router\.replace\(/);
    expect(src).not.toMatch(/router\.push\(/);
    // And it must not hand-write the URL: that desynchronises the router from the
    // address bar, so a link back to the stale URL navigates nowhere.
    expect(src).not.toMatch(/history\.replaceState\(/);
  });

  it('re-filters when a NAVIGATION changes the provider, not only on mount', () => {
    // The footer's Models column is on this page, so clicking "Grok" from /models
    // keeps this component mounted and only changes the prop. useState alone would
    // ignore it: the URL would change and the list would not.
    const { rerender } = render(<ModelsCatalog initialProvider={null} />);
    expect(screen.getByRole('button', { name: /^All/ })).toHaveAttribute('aria-pressed', 'true');

    rerender(<ModelsCatalog initialProvider="xai" />);

    expect(screen.getByRole('button', { name: /xAI/ })).toHaveAttribute('aria-pressed', 'true');
    const rows = within(screen.getByRole('list', { name: 'Models' })).getAllByRole('listitem');
    expect(rows).toHaveLength(XAI.length);
  });
});
