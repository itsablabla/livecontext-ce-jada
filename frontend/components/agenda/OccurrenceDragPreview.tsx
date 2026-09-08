'use client';

import { MoveRight } from 'lucide-react';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { formatDayShortInZone, formatTimeInZone, isSameDay } from '@/lib/utils/agendaTime';
import { occurrenceAccent, resourceIcon } from './agendaVisuals';

interface OccurrenceDragPreviewProps {
  occurrence: AgendaOccurrence;
  /** Where it would land right now, or null while the pointer is over no cell. */
  proposedStart: Date | null;
  timezone: string;
}

/**
 * The card that follows the pointer while an occurrence is being dragged.
 *
 * <p>Without one there was nothing to see: dnd-kit moves no DOM of its own, the chip stays
 * in its cell (dimmed, because it is the thing being moved) and the only feedback was a
 * ring appearing on the cell underneath. On a month grid that ring is one square among
 * forty-two, so the gesture read as "nothing is happening" and the drop arrived as a
 * surprise. Rendered inside the {@code DragOverlay}, which is what actually tracks the
 * pointer.
 *
 * <p>It says the TIME rather than just repeating the chip, because time is the only thing a
 * move changes. The old value stays, struck through, next to the one the current cell would
 * give it: "09:00 -> 14:00" answers the question the user is dragging in order to answer,
 * and it agrees with the dialog that opens on drop, since both read the same
 * {@code resolveDropStart}. The proposed value is dropped, not guessed, whenever the pointer
 * is over no cell at all - a preview that kept showing the last cell's time would promise a
 * move that releasing there will not make.
 *
 * <p>The day is spelled out only when the drop would change it. In a week or day grid the
 * date is usually the same and printing it twice buries the hours, which is what the reader
 * is actually comparing.
 *
 * <p>Not draggable and not focusable: it is a picture of the chip, and registering a second
 * draggable under the same occurrence id would collide with the real one.
 */
export function OccurrenceDragPreview({
  occurrence,
  proposedStart,
  timezone,
}: OccurrenceDragPreviewProps) {
  const accent = occurrenceAccent(occurrence);
  const Icon = resourceIcon(occurrence.resourceType);
  const originalStart = new Date(occurrence.startAt);
  const currentTime = formatTimeInZone(originalStart, timezone);
  const movesDay = proposedStart !== null && !isSameDay(proposedStart, originalStart, timezone);

  return (
    <div
      // `pointer-events-none` so the card under the cursor can never be the thing dnd-kit
      // hit-tests: it sits exactly over the drop target by construction.
      className="pointer-events-none w-max max-w-[16rem] rotate-2 opacity-95"
      aria-hidden="true"
    >
      <div
        className={`flex items-center gap-1.5 rounded-md px-2 py-1 shadow-2xl
                    ring-2 ring-[var(--accent-primary)] ${accent.chip}`}
      >
        <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${accent.dot}`} />

        <span
          className={`shrink-0 text-xs tabular-nums ${
            proposedStart ? 'line-through opacity-50' : 'opacity-80'
          }`}
        >
          {currentTime}
        </span>

        {proposedStart && (
          <>
            <MoveRight className="h-3 w-3 shrink-0 opacity-60" />
            <span className="shrink-0 text-xs font-semibold tabular-nums">
              {movesDay ? `${formatDayShortInZone(proposedStart, timezone)} ` : ''}
              {formatTimeInZone(proposedStart, timezone)}
            </span>
          </>
        )}

        <span className="min-w-0 flex-1 truncate text-xs">{occurrence.name}</span>
        <Icon className="h-3 w-3 shrink-0 opacity-70" />
      </div>
    </div>
  );
}
