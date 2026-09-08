'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { menuSurfaceClass } from '@/components/ui/menu';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  addMonths,
  dayKey,
  monthGridDays,
  monthNames,
  weekdayHeaders,
  zonedParts,
  zonedTimeToInstant,
} from '@/lib/utils/agendaTime';
import { useDayKey } from '@/hooks/useNow';

interface DatePickerPopoverProps {
  /** The period the calendar is currently showing; the picker opens on its month. */
  anchor: Date;
  timezone: string;
  weekStartsOn: 0 | 1;
  /** What the bar already says, e.g. "September 2026" - the trigger's own label. */
  title: string;
  onPick: (date: Date) => void;
}

/**
 * The bar's title, opened as a small calendar.
 *
 * <p>The arrows step one period at a time, which is right for "next week" and useless for
 * "the 3rd of March next year": twelve clicks, each one a fetch. The title is the obvious
 * place to ask, because it is the thing on screen that already names where you are.
 *
 * <p>Two ways in, deliberately: the month grid for a date you can see, and month + year
 * dropdowns for one you cannot. The year list is a window around the year in view rather
 * than a fixed range, so it re-centres as you travel and there is no edge to run into.
 */
const YEAR_SPAN = 10;

export function DatePickerPopover({
  anchor,
  timezone,
  weekStartsOn,
  title,
  onPick,
}: DatePickerPopoverProps) {
  const t = useTranslations('agenda');
  const [open, setOpen] = useState(false);

  // The month being BROWSED, which is not the period being displayed: paging through
  // months inside the picker must not move the calendar behind it - nothing is chosen
  // until a day is clicked. It re-seeds from the anchor each time the popover opens.
  const [browsing, setBrowsing] = useState<Date>(anchor);
  const openPicker = (next: boolean) => {
    if (next) setBrowsing(anchor);
    setOpen(next);
  };

  const parts = zonedParts(browsing, timezone);
  const todayKey = useDayKey(timezone);
  const anchorKey = dayKey(anchor, timezone);

  const days = useMemo(
    () => monthGridDays(browsing, timezone, weekStartsOn),
    [browsing, timezone, weekStartsOn],
  );
  const headers = useMemo(() => weekdayHeaders(timezone, weekStartsOn), [timezone, weekStartsOn]);
  const months = useMemo(() => monthNames(timezone), [timezone]);
  const years = useMemo(
    () => Array.from({ length: YEAR_SPAN * 2 + 1 }, (_, i) => parts.year - YEAR_SPAN + i),
    [parts.year],
  );

  /** Move the browsed month without touching the calendar behind the popover. */
  const browseTo = (year: number, month: number) => {
    // Day 1 at noon: the first of a month exists everywhere, and noon is clear of every
    // DST boundary, so no zone can push this into the neighbouring month.
    setBrowsing(zonedTimeToInstant(timezone, year, month, 1, 12, 0));
  };

  const pick = (day: Date) => {
    onPick(day);
    setOpen(false);
  };

  return (
    // `min-w-0` + `max-w-full`: a week period spells out both endpoints ("Monday,
    // August 31, 2026 to Sunday, September 6, 2026"), which is 460px of
    // `whitespace-nowrap` button - wider than a phone. The heading gives way and
    // the label truncates instead of pushing the page into a sideways scroll.
    <h1 className="mr-auto min-w-0 max-w-full text-base font-medium">
    <Popover open={open} onOpenChange={openPicker}>
      <PopoverTrigger asChild>
        {/* The heading IS the control, so the button lives INSIDE the h1 rather than the
            other way round: `h1` is flow content and is not allowed inside `button`, and
            wrapping keeps the page's level-1 heading where a screen reader expects it.

            The accessible name carries the PERIOD as well as the action. `aria-label` wins
            over descendant text, so naming this "Jump to a date" alone deleted the one
            element that told a screen-reader user which month they were reading. */}
        <Button
          variant="ghost"
          // The variant's hover is the app's legacy INVERSION (near-black ground, light
          // text). That is right for an icon button and wrong for a heading: the title
          // would flip to a black slab under the pointer, which is what a menu looks like,
          // not what a page title looks like. Overridden to the neutral row-hover ground so
          // it still comes from `Button` - the bar has one button dialect - while reading
          // as the heading it replaced.
          className="h-8 min-w-0 max-w-full gap-1.5 px-2 text-base font-medium text-theme-primary
                     hover:bg-surface-hover hover:text-theme-primary"
          aria-label={t('datePicker.openWith', { period: title })}
          title={t('datePicker.open')}
        >
          <span className="truncate">{title}</span>
          <CalendarDays className="h-3.5 w-3.5 text-theme-muted" aria-hidden="true" />
        </Button>
      </PopoverTrigger>

      <PopoverContent align="start" className={`w-72 ${menuSurfaceClass} p-3`}>
        <div className="mb-2 flex items-center gap-1">
          <Button
            variant="outline"
            size="sm"
            className="h-8 w-8 shrink-0 p-0"
            aria-label={t('datePicker.previousMonth')}
            onClick={() => setBrowsing(addMonths(browsing, -1, timezone))}
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </Button>

          <Select
            value={String(parts.month)}
            onValueChange={(value) => browseTo(parts.year, Number(value))}
          >
            <SelectTrigger className="h-8 flex-1 text-xs" aria-label={t('datePicker.month')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {months.map((name, index) => (
                <SelectItem key={name} value={String(index + 1)} className="text-xs">
                  {name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            value={String(parts.year)}
            onValueChange={(value) => browseTo(Number(value), parts.month)}
          >
            <SelectTrigger className="h-8 w-20 text-xs" aria-label={t('datePicker.year')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {years.map((year) => (
                <SelectItem key={year} value={String(year)} className="text-xs tabular-nums">
                  {year}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Button
            variant="outline"
            size="sm"
            className="h-8 w-8 shrink-0 p-0"
            aria-label={t('datePicker.nextMonth')}
            onClick={() => setBrowsing(addMonths(browsing, 1, timezone))}
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </Button>
        </div>

        <div className="grid grid-cols-7 gap-0.5">
          {headers.map((header, index) => (
            <div key={index} className="py-1 text-center text-xs font-medium text-theme-muted">
              {header}
            </div>
          ))}
          {days.map((day) => {
            const key = dayKey(day, timezone);
            const dayParts = zonedParts(day, timezone);
            const outside = dayParts.month !== parts.month;
            const isToday = key === todayKey;
            const isAnchor = key === anchorKey;
            return (
              <button
                key={key}
                type="button"
                onClick={() => pick(day)}
                aria-current={isAnchor ? 'date' : undefined}
                className={`rounded-md py-1 text-xs tabular-nums transition-colors
                            ${isAnchor
                              ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                              : isToday
                                ? 'text-[var(--accent-primary)] font-medium hover:bg-surface-hover'
                                : outside
                                  ? 'text-theme-muted opacity-50 hover:bg-surface-hover'
                                  : 'text-theme-secondary hover:bg-surface-hover'}`}
              >
                {dayParts.day}
              </button>
            );
          })}
        </div>
      </PopoverContent>
    </Popover>
    </h1>
  );
}
