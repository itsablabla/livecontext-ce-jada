/**
 * @vitest-environment jsdom
 *
 * The gallery card's "Use this template" CTA.
 *
 * <p>It used to hand-write the app's solid button into its own className
 * (`bg-slate-900 … dark:bg-white … text-white`), which is the one spelling no
 * edit to the Button variant table ever reaches: the app-wide scan in
 * components/ui/__tests__/solidButtonFill.test.ts says nothing paints its own
 * fill any more, and this says what this card paints INSTEAD.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => Object.assign((key: string) => key, { rich: (key: string) => key }),
}));

vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/DataSourceColumnIcons', () => ({
  DataSourceColumnIcons: () => null,
  normalizeColumnType: (value: string) => value,
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/lib/templates/hydrate', () => ({
  templateCopy: () => ({ title: 'Daily digest', description: 'What it does', teaches: [] }),
}));

import { TemplateCard } from '../TemplateCard';

const meta = {
  slug: 'daily-digest',
  kind: 'workflow',
  order: 1,
  difficulty: 'beginner',
  icon: 'zap',
  runnable: true,
  teachesCount: 0,
  nodeKinds: [],
} as never;

afterEach(cleanup);

function renderCard(props: { busy?: boolean; disabled?: boolean } = {}) {
  render(
    <TemplateCard
      meta={meta}
      busy={props.busy ?? false}
      disabled={props.disabled ?? false}
      onSelect={() => undefined}
    />,
  );
  return screen.getByRole('button');
}

describe('the template card CTA', () => {
  it('is the app button, filled with the accent tokens rather than its own slate', () => {
    const cta = renderCard();

    // The card takes the variant by omission, so the classes are what says which
    // one it got - `data-variant` is only stamped when a caller names one.
    expect(cta.className).toContain('bg-[var(--accent-primary)]');
    expect(cta.className).toContain('text-[var(--accent-foreground)]');
    expect(cta.className).not.toContain('bg-slate-900');
  });

  it('does not dim twice while it works', () => {
    // The card already carries `opacity-60` while a template is being created,
    // and the Button's own `disabled:opacity-60` multiplies into it - 0.36 on the
    // label and its spinner, which is under what the text stays readable at.
    const cta = renderCard({ busy: true });

    expect(cta.getAttribute('aria-busy')).toBe('true');
    expect((cta as HTMLButtonElement).disabled).toBe(true);
    expect(cta.className).toContain('disabled:opacity-100');
  });
});
