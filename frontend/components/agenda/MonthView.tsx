'use client';

import { useMemo, useState } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { Plus } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import {
  dayKey,
  formatFullDate,
  monthGridDays,
  weekdayHeaders,
  zonedParts,
} from '@/lib/utils/agendaTime';
import { useDayKey } from '@/hooks/useNow';
import { OccurrenceChip } from './OccurrenceChip';

interface MonthViewProps {
  anchor: Date;
  timezone: string;
  weekStartsOn: 0 | 1;
  showWeekends: boolean;
  compact: boolean;
  occurrencesByDay: Map<string, AgendaOccurrence[]>;
  /** Schedule the user navigated here to find; its chips are ringed. */
  focusScheduleId?: string | null;
  /** False for an org VIEWER: chips are not draggable, matching the row menu. */
  canMutate: boolean;
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
  /** Clicking an EMPTY day offers to schedule something on it. Absent for a VIEWER. */
  onCreate?: (day: Date) => void;
}

/**
 * The month grid: always six weeks, so switching months never makes the page jump.
 *
 * <p>Each day is a drop target. A drop carries the DAY only - the exact time is settled in
 * the move dialog, which is both easier to hit than a pixel-precise slot and the place the
 * "this run or all of them" question has to be asked anyway.
 */
export function MonthView({
  anchor,
  timezone,
  weekStartsOn,
  showWeekends,
  compact,
  occurrencesByDay,
  focusScheduleId,
  canMutate,
  onSelect,
  onCreate,
}: MonthViewProps) {
  const t = useTranslations('agenda');
  const days = useMemo(
    () => monthGridDays(anchor, timezone, weekStartsOn),
    [anchor, timezone, weekStartsOn],
  );
  const headers = useMemo(
    () => weekdayHeaders(timezone, weekStartsOn, undefined),
    [timezone, weekStartsOn],
  );
  const anchorMonth = zonedParts(anchor, timezone).month;
  // A key, not an instant: the month grid needs one fact from the clock (which cell is
  // today) and it changes once a day, so this re-renders the 42 droppable cells and their
  // chips once a day rather than once a minute. See useDayKey.
  const todayKey = useDayKey(timezone);

  const visibleIndexes = headers
    .map((_, index) => index)
    .filter((index) => {
      if (showWeekends) return true;
      const weekday = (index + weekStartsOn) % 7;
      return weekday !== 0 && weekday !== 6;
    });

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-theme">
      <div
        className="grid shrink-0 border-b border-theme bg-theme-secondary"
        style={{ gridTemplateColumns: `repeat(${visibleIndexes.length}, minmax(0, 1fr))` }}
      >
        {visibleIndexes.map((index) => (
          <div key={index} className="px-2 py-1.5 text-xs font-medium text-theme-muted">
            {headers[index]}
          </div>
        ))}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div
          className="grid h-full"
          style={{
            gridTemplateColumns: `repeat(${visibleIndexes.length}, minmax(0, 1fr))`,
            gridAutoRows: 'minmax(6.5rem, 1fr)',
          }}
        >
          {days
            .filter((_, index) => visibleIndexes.includes(index % 7))
            .map((day) => (
              <DayCell
                key={dayKey(day, timezone)}
                day={day}
                timezone={timezone}
                compact={compact}
                dimmed={zonedParts(day, timezone).month !== anchorMonth}
                isToday={dayKey(day, timezone) === todayKey}
                occurrences={occurrencesByDay.get(dayKey(day, timezone)) ?? []}
                focusScheduleId={focusScheduleId}
            canMutate={canMutate}
                onSelect={onSelect}
                moreLabel={(n) => t('moreCount', { n })}
                lessLabel={t('showLess')}
                onCreate={onCreate}
                createLabel={t('create.slotActionOn', { date: formatFullDate(day, timezone) })}
              />
            ))}
        </div>
      </div>
    </div>
  );
}

function DayCell({
  day,
  timezone,
  compact,
  dimmed,
  isToday,
  occurrences,
  focusScheduleId,
  canMutate,
  onSelect,
  moreLabel,
  lessLabel,
  onCreate,
  createLabel,
}: {
  day: Date;
  timezone: string;
  compact: boolean;
  dimmed: boolean;
  isToday: boolean;
  occurrences: AgendaOccurrence[];
  focusScheduleId?: string | null;
  /** False for an org VIEWER: chips are not draggable, matching the row menu. */
  canMutate: boolean;
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
  moreLabel: (n: number) => string;
  lessLabel: string;
  onCreate?: (day: Date) => void;
  createLabel: string;
}) {
  const key = dayKey(day, timezone);
  const { setNodeRef, isOver } = useDroppable({ id: `day:${key}`, data: { day: day.toISOString() } });
  const [expanded, setExpanded] = useState(false);

  // A dense day would otherwise blow the row height out and push the rest of the month
  // off screen; the overflow stays reachable behind one click.
  const visibleLimit = compact ? 3 : 4;
  const visible = expanded ? occurrences : occurrences.slice(0, visibleLimit);
  const hidden = occurrences.length - visible.length;

  return (
    <div
      ref={setNodeRef}
      // `@container`, like the hour cells: a month cell is ~50px wide on a phone and the
      // chips inside it read that, not the viewport.
      className={`@container flex min-h-0 flex-col gap-0.5 border-b border-r border-theme p-1
                  ${dimmed ? 'bg-theme-secondary/40' : ''}
                  ${isOver ? 'ring-2 ring-inset ring-[var(--accent-primary)]' : ''}`}
    >
      <div className="flex items-center justify-between px-1">
        <span
          className={`text-xs tabular-nums ${
            isToday
              ? 'flex h-5 w-5 items-center justify-center rounded-full bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
              : dimmed
                ? 'text-theme-muted opacity-60'
                : 'text-theme-secondary'
          }`}
        >
          {zonedParts(day, timezone).day}
        </span>
      </div>

      {/* Expanding a day made its extra runs UNREACHABLE: the cell keeps the row's height
          (`gridAutoRows: minmax(6.5rem, 1fr)`), so "+5 more" swapped a truncated list for a
          clipped one - the chips existed, below the fold, with no way to reach them. It
          scrolls once expanded.

          Only once expanded, which is the same rule the hour grid learned the hard way: a
          month is 42 cells, and making every one of them a scroll container hands the wheel
          to whichever cell the pointer happens to be over, so the MONTH stops scrolling.
          Collapsed cells are plain divs and are never offered the event. `overscroll-y-auto`
          (not `contain`) lets a cell scrolled to its end pass the rest of the gesture back
          to the grid. */}
      <div
        className={`flex min-h-0 flex-1 flex-col gap-0.5
                    ${expanded ? 'overflow-y-auto overscroll-y-auto' : 'overflow-hidden'}`}
      >
        {visible.map((occurrence) => (
          <OccurrenceChip
            key={occurrence.id}
            occurrence={occurrence}
            timezone={timezone}
            compact={compact}
            highlighted={Boolean(focusScheduleId) && occurrence.scheduleId === focusScheduleId}
            canMutate={canMutate}
            onSelect={onSelect}
          />
        ))}
        {hidden > 0 && (
          <button
            type="button"
            onClick={() => setExpanded(true)}
            className="px-1.5 text-left text-[11px] text-theme-muted hover:text-theme-primary"
          >
            {moreLabel(hidden)}
          </button>
        )}
        {/* The way back. Expanding replaces the "+N more" button with nothing, so without
            this the cell stays expanded for the rest of the visit and the day that was one
            line tall is now a scrollbox the user cannot undo. */}
        {expanded && occurrences.length > visibleLimit && (
          <button
            type="button"
            onClick={() => setExpanded(false)}
            className="px-1.5 text-left text-[11px] text-theme-muted hover:text-theme-primary"
          >
            {lessLabel}
          </button>
        )}
        {/* Whatever is left of the day, offered. This used to be an overlay on empty days
            only, which meant a day holding one run could not be scheduled into from the
            calendar at all - and the overlay had to be pushed below the date row to stop it
            painting the number out. As the last flow child it takes only the space the
            chips left, sits on nothing, and covers no date.

            Only while the day has space to give: a collapsed cell showing its full
            `visibleLimit` has none, and rendering a zero-height button there would take a
            2px gap off a list that is already `overflow-hidden` and clip the last chip. */}
        {onCreate && visible.length < visibleLimit && (
          <button
            type="button"
            onClick={() => onCreate(day)}
            aria-label={createLabel}
            title={createLabel}
            className="flex min-h-0 flex-1 items-center justify-center rounded-md opacity-0
                       transition-opacity hover:bg-surface-hover hover:opacity-100
                       focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2
                       focus-visible:ring-[var(--accent-primary)]"
          >
            <Plus className="h-4 w-4 text-theme-muted" aria-hidden="true" />
          </button>
        )}
      </div>
    </div>
  );
}
