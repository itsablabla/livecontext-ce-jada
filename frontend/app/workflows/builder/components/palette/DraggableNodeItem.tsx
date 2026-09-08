'use client';

import * as React from 'react';
import { GripVertical, ChevronRight, ArrowRight, Lock } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { PaletteItemTooltipContent, type PaletteItemBadge } from './PaletteItemTooltipContent';
import { NodeIcon } from '../nodes/shared';
import { AvatarDisplay } from '@/components/agents';
import type { NodeFamily } from '../../nodes/nodeClasses';
import type { BuilderNodeKind } from '../../types';

export type DraggableNodeItemProps = {
  /** Unique key for the item */
  id: string;
  /** Display label */
  label: string;
  /** Optional description */
  description?: string | null;
  /** Click handler */
  onClick?: () => void;
  /** Drag data to set on dragStart */
  dragData?: object;
  /** Whether dragging is disabled */
  disableDrag?: boolean;
  /** Show navigation arrow (ChevronRight or ArrowRight) */
  showArrow?: boolean;
  /** Arrow type: 'chevron' (default) or 'arrow' */
  arrowType?: 'chevron' | 'arrow';
  /** Secondary info (e.g., "5 tools") */
  secondaryInfo?: string;
  // NodeIcon props
  nodeId?: string;
  nodeKind?: BuilderNodeKind;
  nodeFamily?: NodeFamily;
  bgClassName?: string;
  iconSlug?: string;
  isMcp?: boolean;
  iconSize?: 'sm' | 'md' | 'lg';
  /** Agent avatar URL - replaces NodeIcon when provided */
  avatarUrl?: string;
  /** Custom icon element - replaces NodeIcon entirely when provided */
  iconOverride?: React.ReactNode;
  /**
   * The plan this node needs, when the account does not have it. It marks the
   * row and nothing more: the node stays draggable and clickable on purpose.
   *
   * <p>Refusing here was the first design and it was a false floor. Dragging the
   * integration and then picking a restricted endpoint in the inspector reached
   * exactly the same place with no marking at all, so the palette was blocking
   * one route out of two while claiming to be the boundary. The boundary is the
   * backend; the builder's job is to say so, on the canvas, where the node ends
   * up whichever route it took.
   */
  lockedPlan?: string | null;
  /**
   * What the row IS, when the palette knows and the props cannot say.
   *
   * The group tile for "Triggers" and the "Tables trigger" row inside that
   * group are both `nodeKind: 'entry'` with a chevron, so no rule over the
   * props can separate a folder OF triggers from a trigger that drills down:
   * ranking the kind first labelled the folder "Trigger", ranking the chevron
   * first labelled the three drill-down triggers "Category". The branch that
   * renders group tiles is the only thing that knows, so it says so, and every
   * other row is still resolved from what it already passes.
   */
  paletteRole?: PaletteItemBadge;
};

/**
 * How far the hover card sits from the panel it opens beside. The Run tab's
 * rows run edge to edge, so its `sideOffset={8}` puts the card 8px to the left
 * of the panel and the two read as one component.
 */
export const PALETTE_CARD_GAP_PX = 8;

/**
 * How far a palette row is indented inside that same panel (`pl-3` on every
 * section in NodeCreatorPanel, lists and the Frequently Used grid alike).
 *
 * It has to be added back to the gap above, because Radix measures `sideOffset`
 * from the trigger's own box, not from the panel: with the plain 8 the card
 * landed 4px OVER the panel edge, flush against it, while the Run tab's card
 * had a visible gap. Same prop, same number, different anchor - which is why
 * copying the Run tab's literals was not enough to copy its placement.
 *
 * It is exact for the single-column lists, which is where a reader compares the
 * two panels. It cannot be exact for the RIGHT column of the Frequently Used
 * grid: those tiles start half a panel in, so no single offset places their
 * card beside the panel. They are simply 12px better than they were.
 */
export const PALETTE_LIST_ROW_INSET_PX = 12;

/**
 * Reusable draggable node item component for the NodeCreatorPanel.
 * Handles drag & drop, click events, tooltips, and consistent styling.
 */
export function DraggableNodeItem({
  id,
  label,
  description,
  onClick,
  dragData,
  disableDrag = false,
  showArrow = false,
  arrowType = 'chevron',
  secondaryInfo,
  nodeId,
  nodeKind,
  nodeFamily,
  bgClassName,
  iconSlug,
  isMcp = false,
  iconSize = 'md',
  avatarUrl,
  iconOverride,
  lockedPlan,
  paletteRole,
}: DraggableNodeItemProps) {
  const isLocked = !!lockedPlan;
  const handleDragStart = React.useCallback(
    (e: React.DragEvent) => {
      if (disableDrag || !dragData) {
        e.preventDefault();
        return;
      }
      e.stopPropagation();
      e.dataTransfer.setData('application/reactflow', JSON.stringify(dragData));
      e.dataTransfer.effectAllowed = 'copy';

      // Create custom drag image
      const dragElement = e.currentTarget as HTMLElement;
      const dragImage = dragElement.cloneNode(true) as HTMLElement;
      dragImage.style.position = 'absolute';
      dragImage.style.top = '-1000px';
      dragImage.style.opacity = '0.8';
      dragImage.style.pointerEvents = 'none';
      document.body.appendChild(dragImage);

      const rect = dragElement.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      e.dataTransfer.setDragImage(dragImage, x, y);

      setTimeout(() => {
        if (document.body.contains(dragImage)) {
          document.body.removeChild(dragImage);
        }
      }, 0);
    },
    [disableDrag, dragData]
  );

  const cursorClass = disableDrag ? 'cursor-pointer' : 'cursor-grab active:cursor-grabbing';

  /* What the hover card says the row is. Read off the props the palette already
     passes rather than a new one at 15 call sites, and resolved in this order
     because a row can satisfy several clauses at once:

       - a declared role wins. Only the group-tile branches set one, and only
         because nothing in the props distinguishes a folder of triggers from a
         trigger that opens a list (see `paletteRole`);
       - an integration says so rather than "Category", because which API it is
         is the useful half. `isMcp` ALONE does not mean that though: it is an
         icon-source flag that single API endpoints carry too, so the chevron is
         the other half of that test;
       - a trigger is a trigger. The call sites that render real trigger rows
         pass `nodeKind="entry"` and no family, while the featured grid passes
         the family, so BOTH have to be read;
       - anything else with a chevron is a folder, and the rest are plain nodes.

     `disableDrag` decides nothing here: the top-level AI / Flow / Core groups
     navigate AND are draggable, so keying on it badged them as nodes. */
  const badge: PaletteItemBadge = paletteRole
    ?? (isMcp && showArrow ? 'integration'
      : nodeFamily === 'trigger' || nodeKind === 'entry' ? 'trigger'
        : showArrow ? 'category'
          : 'node');
  /* Whether to promise a drag.
     `dragData` is deliberately not consulted: `getPaletteItemDataFromId` never
     returns falsy (it falls back to a bare `flowNode`), so every row carries a
     payload and testing for one only looks like a guard. What decides is
     whether the row REFUSES the gesture, and whether dragging it would produce
     anything worth having: a folder tile technically drags, and dropping one
     leaves a nameless node on the canvas, so it is not offered. An INTEGRATION
     row still is - it really does add the API as one node. */
  const canDrag = !disableDrag && badge !== 'category';

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div
          draggable={!disableDrag}
          onDragStart={handleDragStart}
          onMouseDown={(e) => e.stopPropagation()}
          className={`group flex items-center gap-2 px-2 py-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors active:bg-gray-100 dark:active:bg-gray-800 relative ${cursorClass}`}
          onClick={onClick}
        >
          {!disableDrag && (
            <GripVertical className="absolute left-0 w-3 h-3 text-gray-400 dark:text-gray-500 opacity-0 group-hover:opacity-100 transition-opacity" />
          )}
          {iconOverride ? iconOverride : avatarUrl ? (
            <AvatarDisplay avatarUrl={avatarUrl} name={label} size="md" className="w-9 h-9" />
          ) : (
            <NodeIcon
              nodeId={nodeId || id}
              nodeKind={nodeKind}
              nodeFamily={nodeFamily}
              bgClassName={bgClassName}
              iconSlug={iconSlug}
              isMcp={isMcp}
              alt={label}
              size={iconSize}
            />
          )}
          <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
            <div className="flex-1 min-w-0">
              <div className={`text-sm truncate ${isLocked ? 'text-gray-500 dark:text-gray-400' : 'text-gray-900 dark:text-gray-100'}`}>
                {label}
              </div>
              {description ? (
                <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5 line-clamp-2">
                  {description}
                </div>
              ) : secondaryInfo ? (
                <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5">
                  {secondaryInfo}
                </div>
              ) : null}
            </div>
            {isLocked && (
              // The padlock alone. A pill here competed with the row's own text on
              // a list where most rows are unmarked, and the plan name belongs in
              // the sentence on the node, not repeated on every palette row.
              // `role="img"` with the name: an SVG carrying only an aria-label is
              // not reliably exposed, and this label is the ONLY place the required
              // plan is reachable without a pointer - the hover card that spells it
              // out cannot be opened from the keyboard.
              <Lock
                role="img"
                className="h-3.5 w-3.5 flex-shrink-0 text-amber-500"
                aria-label={lockedPlan ?? undefined}
              />
            )}
            {showArrow && (
              <div className="flex items-center gap-2 text-gray-400 dark:text-gray-500 flex-shrink-0">
                {arrowType === 'arrow' ? (
                  <ArrowRight className="w-4 h-4" />
                ) : (
                  <ChevronRight className="w-4 h-4" />
                )}
              </div>
            )}
          </div>
        </div>
      </TooltipTrigger>
      {/* Same placement as the Run tab's step tooltip, so the card opens on the
          same side and at the same distance whichever of the two panels is on
          screen. Rendered for every row, description or not: the badge and the
          click/drag line are worth reading on their own, and a list where only
          some rows answer a hover is worse than one where they all do.

          Everything it carries is SUPPLEMENTARY on purpose, because the row it
          belongs to is not focusable and a pointer is therefore the only way to
          open it: the name and the description are already on the row, and the
          plan a locked row needs is on the padlock's `aria-label`. Making the
          row focusable would put several hundred palette entries into the tab
          order to disclose a badge and a gesture hint, which is the worse
          trade. */}
      <TooltipContent side="left" sideOffset={PALETTE_CARD_GAP_PX + PALETTE_LIST_ROW_INSET_PX} align="center" className="px-3 py-2.5">
        <PaletteItemTooltipContent
          label={label}
          description={description}
          secondaryInfo={secondaryInfo}
          badge={badge}
          lockedPlan={lockedPlan}
          opensList={showArrow}
          draggable={canDrag}
        />
      </TooltipContent>
    </Tooltip>
  );
}

export default DraggableNodeItem;
