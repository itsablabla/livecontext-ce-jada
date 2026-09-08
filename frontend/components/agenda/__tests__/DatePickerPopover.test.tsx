/**
 * @vitest-environment jsdom
 *
 * That the calendar behind the title can take you anywhere, and moves nothing until you
 * pick a day.
 *
 * The arrows step one period at a time. Reaching March next year that way is twelve clicks
 * and twelve fetches, which is why the title opens a calendar with month and year
 * dropdowns. Two properties make it usable and are easy to get wrong:
 *
 * - browsing is not choosing. Paging months inside the popover must leave the calendar
 *   behind it exactly where it was; only a day click moves it.
 * - the year list is a WINDOW around the year in view, not a fixed range, so there is no
 *   edge to run into - travelling re-centres it.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import * as React from 'react';
import { DatePickerPopover } from '../DatePickerPopover';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

const ANCHOR = new Date('2026-09-03T12:00:00Z');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(ANCHOR);
});

afterEach(() => {
  vi.useRealTimers();
});

function renderPicker(onPick: (d: Date) => void = () => {}) {
  return render(
    <DatePickerPopover
      anchor={ANCHOR}
      timezone="UTC"
      weekStartsOn={1}
      title="September 2026"
      onPick={onPick}
    />,
  );
}

// The trigger is named with the PERIOD as well as the action, so a screen-reader user is
// told which month they are on; the mocked translator renders the key plus its params.
const open = () => fireEvent.click(screen.getByRole('button', { name: /datePicker.openWith/ }));

/** The day buttons of the grid, which are the only numeric-labelled controls in it. */
const dayButtons = () =>
  screen.getAllByRole('button').filter((el) => /^\d{1,2}$/.test(el.textContent ?? ''));

describe('the title opens a calendar', () => {
  it('is named for the period it is showing, not only for what it does', () => {
    // `aria-label` wins over the button's text, so naming it "jump to a date" alone deleted
    // the one thing that told a screen-reader user WHICH month they were reading. Asserted
    // on the rendered period, not on the key: a locale that dropped {period} would pass a
    // key-shaped assertion while re-breaking this.
    renderPicker();

    expect(screen.getByRole('button', { name: /September 2026/ })).toBeTruthy();
  });

  it('shows the month the calendar is on, as a full six-week grid', () => {
    renderPicker();
    open();

    // Always 42 cells: a picker whose height jumps between months makes the day you were
    // aiming for move under the pointer.
    expect(dayButtons()).toHaveLength(42);
  });

  it('hands back the day that was clicked, resolved in the display zone', () => {
    const picked: Date[] = [];
    renderPicker((d) => picked.push(d));
    open();

    fireEvent.click(dayButtons().find((el) => el.textContent === '17')!);

    expect(picked).toHaveLength(1);
    expect(picked[0].toISOString().slice(0, 10)).toBe('2026-09-17');
  });

  it('closes on a pick, so the calendar behind it is readable again', () => {
    renderPicker();
    open();
    fireEvent.click(dayButtons()[10]);

    expect(screen.queryByLabelText('datePicker.month')).toBeNull();
  });

  it('browses months without moving the calendar behind it', () => {
    // The property that makes the picker safe to explore: nothing is chosen until a day is
    // clicked, so paging must not fire a period change (each one is a fetch).
    const picked: Date[] = [];
    renderPicker((d) => picked.push(d));
    open();

    fireEvent.click(screen.getByRole('button', { name: 'datePicker.nextMonth' }));
    fireEvent.click(screen.getByRole('button', { name: 'datePicker.nextMonth' }));

    expect(picked).toHaveLength(0);
    // October then November: the 31st exists in October, and browsing landed past it.
    fireEvent.click(dayButtons().find((el) => el.textContent === '5')!);
    expect(picked[0].toISOString().slice(0, 10)).toBe('2026-11-05');
  });

  it('offers a window of years around the one in view, not a fixed range', () => {
    renderPicker();
    open();

    const year = screen.getByLabelText('datePicker.year');
    fireEvent.click(year);

    const options = within(document.body).getAllByRole('option').map((el) => el.textContent);
    expect(options).toContain('2016');
    expect(options).toContain('2036');
    expect(options).not.toContain('2015');
  });

  it('reopens on the period being read, not on the month left behind last time', () => {
    renderPicker();
    open();
    fireEvent.click(screen.getByRole('button', { name: 'datePicker.nextMonth' }));
    fireEvent.keyDown(document.body, { key: 'Escape' });

    open();

    // September, not the October the popover was left on. Asserted on the month CONTROL:
    // both grids happen to contain exactly one "31" (Aug 31 / Oct 31), so counting those
    // would pass either way - a test that cannot fail is worse than no test.
    expect(screen.getByLabelText('datePicker.month').textContent).toContain('September');
    expect(screen.getByLabelText('datePicker.month').textContent).not.toContain('October');
  });

  it('gives way instead of pushing the page sideways when the period is spelled out', () => {
    // A week period names both endpoints ("Monday, August 31, 2026 to Sunday,
    // September 6, 2026"): 460px of whitespace-nowrap button, wider than a
    // phone, and it took the whole agenda into a horizontal scroll. The heading
    // and the button can shrink, and the label truncates inside them.
    render(
      <DatePickerPopover
        anchor={ANCHOR}
        timezone="UTC"
        weekStartsOn={1}
        title="Monday, August 31, 2026 to Sunday, September 6, 2026"
        onPick={() => {}}
      />,
    );

    const label = screen.getByText('Monday, August 31, 2026 to Sunday, September 6, 2026');
    expect(label.className).toContain('truncate');

    const button = label.closest('button');
    expect(button?.className).toContain('min-w-0');
    expect(button?.className).toContain('max-w-full');

    const heading = label.closest('h1');
    expect(heading?.className).toContain('min-w-0');
    expect(heading?.className).toContain('max-w-full');
  });
});
