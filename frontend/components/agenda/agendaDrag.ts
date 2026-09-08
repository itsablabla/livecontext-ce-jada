import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { zonedParts, zonedTimeToInstant } from '@/lib/utils/agendaTime';

/** What a droppable cell carries: always a day, plus an hour in the time grids. */
export interface AgendaDropTarget {
  day?: string;
  hour?: number;
}

/**
 * Where a dropped occurrence lands.
 *
 * <p>The month grid drops on a DAY and the week/day grids drop on a DAY AND HOUR, so the
 * same gesture has to mean two different things: keep the time of day when only a date
 * changed, and take the slot's hour when the user aimed at one. Getting that backwards is
 * silently wrong rather than broken - the dialog opens either way, pre-filled with a time
 * that is off by hours, and a user who trusts the pre-fill confirms it.
 *
 * <p>All of it resolves in the DISPLAY timezone, not the browser's. A user in Paris reading
 * an agenda pinned to New York drops on the cell they can see; computing the instant from
 * local parts would move the run by the offset between the two.
 *
 * @returns the proposed start, or null when the drop carried no day (a cancelled drag, or
 *          a drop outside any cell) and nothing should open.
 */
export function resolveDropStart(
  occurrence: AgendaOccurrence | undefined,
  target: AgendaDropTarget | undefined,
  timezone: string,
): Date | null {
  if (!occurrence || !target?.day) return null;

  const dropDay = zonedParts(new Date(target.day), timezone);
  const current = zonedParts(new Date(occurrence.startAt), timezone);
  // No hour on the drop means the month grid: the user moved the DATE and said nothing
  // about the time, so the occurrence keeps the one it had, to the minute.
  const hour = target.hour ?? current.hour;
  const minute = target.hour !== undefined ? 0 : current.minute;

  return zonedTimeToInstant(timezone, dropDay.year, dropDay.month, dropDay.day, hour, minute);
}
