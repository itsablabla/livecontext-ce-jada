'use client';

import React from 'react';
import { Binary } from 'lucide-react';
import type { VisualCellProps } from './types';

/**
 * An embedding column's cell. Always read-only.
 *
 * <p><b>Why it exists at all.</b> Before this component the dispatcher had no case for the type and
 * fell through to the generic editable-text path, which drew a box that a click turned into a text
 * input over an embedding column. That was survivable while vector columns were self-hosted-only;
 * it is not something to hand to every cloud workspace now that a plan unlocks them.
 *
 * <p><b>What `value` actually holds.</b> The embedding itself lives in a dedicated vector table,
 * but the items endpoint merges the WHOLE thing back into each row's data as text
 * (`embedding::text`, no truncation, despite what the server calls a "preview"), so `value` is
 * normally a long string like "[-0.009573,-0.021607,...]" - not undefined, and not an array.
 * Rendering it would be meaningless and slow, and writing it back somewhere else would persist
 * kilobytes of numbers, which is why every write path strips vector columns (toWritableRowData).
 *
 * <p>It shows the dimension when the column declares one, because "vec(1536)" is the one fact
 * about the cell that is both true and useful, and a blank cell reads as missing data.
 */
export function VectorCell({ value, displayConfig }: VisualCellProps) {
  const dimension =
    displayConfig && typeof displayConfig === 'object' && 'dimension' in displayConfig
      ? Number((displayConfig as { dimension?: unknown }).dimension)
      : NaN;

  // An ARRAY only when a caller inlined one. What the items endpoint sends is the embedding as a
  // string, which is deliberately not counted here: the declared dimension is the honest number.
  const inlineLength = Array.isArray(value) ? value.length : null;
  const size = inlineLength ?? (Number.isFinite(dimension) && dimension > 0 ? dimension : null);

  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs text-theme-secondary"
      // The cell carries no editable value, so the label is the whole content: name the type and
      // its width rather than leaving an empty box that reads as a row with data missing.
      title="Embedding, stored outside the row and not editable here"
    >
      <Binary className="h-3 w-3 flex-shrink-0" aria-hidden />
      {size ? `vec(${size})` : 'vector'}
    </span>
  );
}
