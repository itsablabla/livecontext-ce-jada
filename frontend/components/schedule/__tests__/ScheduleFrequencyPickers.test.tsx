/**
 * @vitest-environment jsdom
 *
 * The rule a re-implementation drops.
 *
 * These pickers were moved out of the workflow builder so the agenda could use them, and
 * the module's own docblock names this as the reason: the weekly picker REFUSES to
 * deselect the last remaining day. Without that refusal the cron builder falls back to
 * Monday, so the button looks unselected while the schedule still fires on Mondays - a
 * disagreement between what the form shows and what the platform will do, which is the
 * worst kind of scheduling bug because nothing about it looks wrong.
 *
 * Nothing tested it at its old address. It is tested here because it is now shared: two
 * surfaces would inherit the loss at once.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { WeeklyCustomPicker, DailyCustomPicker, MonthlyCustomPicker } from '../ScheduleFrequencyPickers';

const t = ((key: string) => key) as never;

describe('the weekly picker', () => {
  it('turns a day on and hands back the whole set', () => {
    const onChange = vi.fn();
    render(<WeeklyCustomPicker cron="0 9 * * 1" disabled={false} t={t} onChange={onChange} />);

    fireEvent.click(screen.getByText('weekdayWed'));

    expect(onChange).toHaveBeenCalledWith(9, 0, ['1', '3']);
  });

  it('turns one off while another remains', () => {
    const onChange = vi.fn();
    render(<WeeklyCustomPicker cron="0 9 * * 1,3" disabled={false} t={t} onChange={onChange} />);

    fireEvent.click(screen.getByText('weekdayMon'));

    expect(onChange).toHaveBeenCalledWith(9, 0, ['3']);
  });

  it('refuses to deselect the last day, and says why', () => {
    // The cron builder falls back to Monday for an empty set, so allowing this would leave
    // a picker with nothing selected and a schedule that still fires on Mondays.
    const onChange = vi.fn();
    render(<WeeklyCustomPicker cron="0 9 * * 4" disabled={false} t={t} onChange={onChange} />);
    const lastDay = screen.getByText('weekdayThu');

    fireEvent.click(lastDay);

    expect(onChange).not.toHaveBeenCalled();
    expect(lastDay.hasAttribute('disabled')).toBe(true);
    expect(lastDay.getAttribute('title')).toBe('atLeastOneDay');
  });

  it('carries the chosen days through a TIME change', () => {
    // The time and the days are one cron. An hour edit that dropped the days would quietly
    // move a Tuesday+Thursday schedule to Mondays.
    const onChange = vi.fn();
    render(<WeeklyCustomPicker cron="0 9 * * 2,4" disabled={false} t={t} onChange={onChange} />);

    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '17' } });

    expect(onChange).toHaveBeenCalledWith(17, 0, ['2', '4']);
  });
});

describe('the daily and monthly pickers', () => {
  it('reports the hour and minute the user typed', () => {
    const onChange = vi.fn();
    render(<DailyCustomPicker cron="0 9 * * *" disabled={false} t={t} onChange={onChange} />);

    fireEvent.change(screen.getAllByRole('spinbutton')[1], { target: { value: '45' } });

    expect(onChange).toHaveBeenCalledWith(9, 45);
  });

  it('holds the day of month inside a month', () => {
    // 31 is already a schedule that skips months; 45 is not a date at all.
    const onChange = vi.fn();
    render(<MonthlyCustomPicker cron="0 9 1 * *" disabled={false} t={t} onChange={onChange} />);

    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '45' } });

    expect(onChange).toHaveBeenCalledWith(9, 0, 31);
  });

  it('accepts nothing while the form is submitting', () => {
    const onChange = vi.fn();
    render(<DailyCustomPicker cron="0 9 * * *" disabled t={t} onChange={onChange} />);

    for (const field of screen.getAllByRole('spinbutton')) {
      expect(field.hasAttribute('disabled')).toBe(true);
    }
    expect(onChange).not.toHaveBeenCalled();
  });
});
