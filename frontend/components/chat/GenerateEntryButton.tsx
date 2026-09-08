'use client';

import * as React from 'react';
import { WandSparkles } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { menuItemClass } from '@/components/ui/menu';

/**
 * The control that leads to the studio, wherever making an asset is offered.
 *
 * <p><b>Why this is a component and not a pattern to copy.</b> Every surface
 * that offers a generation has to answer the same two questions before drawing
 * anything: may this reader write here, and does this install serve generation
 * at all. Both were written out per surface, and the per-surface copy is where
 * the answers drift: the second one keeps the button on a 404, or drops it on a
 * 5xx, or renders a disabled control whose reason nobody can reach. Those are
 * not styling differences, so they do not belong to the surfaces.
 *
 * <p>What DOES belong to the surface is how the control looks: a page toolbar
 * has room for a word, a composer's button row has room for an icon, and a
 * composer too narrow for three icons has a menu row. That is the only thing
 * {@link GenerateEntryButtonProps.variant} decides.
 *
 * <p>Renders NOTHING when a generation cannot be started here, and asks the
 * catalogue nothing on behalf of a reader who could not act on the answer.
 *
 * <p>It used to open a dialog, and to prefetch that dialog's chunk on hover. Both are gone with the
 * dialog: the studio is a route, and a route's code is fetched by the router.
 */
export interface GenerateEntryButtonProps {
  /**
   * `toolbar` shows the word beside the icon (dropped under `sm`, where the row
   * gets tight); `icon` is icon-only, for a row that is already all icons;
   * `menuitem` is a row of the app's action menu (`components/ui/menu`), for a
   * composer whose button row got too narrow to hold three icons and merged them
   * into one. The gate above is the reason this is a variant rather than a
   * caller drawing its own row: a menu that listed generation on an install that
   * does not serve it would offer a dead entry.
   */
  variant: 'toolbar' | 'icon' | 'menuitem';
  /** What the control is called, and its accessible name. */
  label: string;
  /**
   * Go to the studio. Named `onOpen` because that is what it is to the reader, and because every
   * caller of this button already spells it that way.
   */
  onOpen: () => void;
}

/** @see the module docblock above for why the gate lives here and not per surface. */
export function GenerateEntryButton({ variant, label, onOpen }: GenerateEntryButtonProps) {
  // The studio's own sentence, restated on the control that leads to it, so the two can never
  // drift into two different facts about the same catalogue.
  const tGeneration = useTranslations('generation');

  // A generation writes a file into the workspace and spends credits, so a
  // read-only VIEWER is not offered one. Asked only of a reader who could act
  // on the answer, so they cost the install no request either.
  const canGenerate = useCanMutateInCurrentOrg();
  // Through the SAME query key and cache the studio reads: a surface and the studio it leads to
  // cost one request between them, not two.
  const { availability } = useGenerationModels(canGenerate);

  // Ties the disabled control to the sentence saying why. Per instance, not a
  // module constant: a page can hold two of these at once (the docked chat
  // beside the main one), and two elements sharing an id would point every
  // `aria-describedby` at the first one's text.
  const reasonId = React.useId();

  // 404: this install does not serve generation. Nothing anyone does in the app
  // changes that, so the control is not offered at all.
  //
  // Every other answer keeps it, INCLUDING 'unknown' (in flight, a 5xx, a
  // connection that never landed): none of those say the feature is absent, and
  // hiding one the install really has is a lie that looks exactly like the
  // truth. It also keeps the row stable, instead of growing a control once the
  // first fetch lands.
  if (!canGenerate || availability === 'absent') return null;

  // Served, but with nothing in it. The feature IS here and an administrator can seed it, so the
  // control stays, visibly unavailable and carrying the reason, rather than vanishing.
  const empty = availability === 'empty';

  return (
    // The reason lives on the WRAPPER, not on the button: a disabled Button
    // carries `pointer-events-none`, so a title on it would never be shown to
    // the person hovering it, and an unavailable action with no reachable
    // explanation reads as a permission they lack.
    <span
      className={variant === 'menuitem' ? 'flex w-full' : 'inline-flex'}
      title={empty ? tGeneration('empty') : label}
    >
      <Button
        variant={variant === 'toolbar' ? 'outline' : 'ghost'}
        size={variant === 'toolbar' ? 'default' : 'icon'}
        className={
          variant === 'toolbar' ? 'px-2.5 sm:px-4'
            : variant === 'menuitem' ? `${menuItemClass} h-auto min-h-9 justify-start`
            : 'h-9 w-9'
        }
        aria-label={label}
        onClick={onOpen}
        disabled={empty}
        aria-describedby={empty ? reasonId : undefined}
      >
        {/* A WAND, not the bare sparkle. `Sparkles` is this app's generic "AI"
            badge - it marks skills, suggestions, onboarding, the avatar picker,
            credits, thirty-odd places - so wearing it here said "something AI"
            and nothing about producing a file. The wand keeps that lineage while
            saying the control MAKES something, and it leaves the plain sparkle
            free to go on meaning "AI" everywhere else. Deliberately not an image
            glyph: the studio also makes video, audio, voice and music, and
            `ImagePlus` would both under-describe it and read as "upload" next to
            the attachment button it sits beside. */}
        <WandSparkles className={variant === 'toolbar' ? 'h-4 w-4 sm:mr-1.5' : 'h-4 w-4 flex-shrink-0'} />
        {variant === 'toolbar' && <span className="hidden sm:inline">{label}</span>}
        {variant === 'menuitem' && <span>{label}</span>}
      </Button>
      {/* The same words, for a reader who never hovers anything. */}
      {empty && (
        <span id={reasonId} className="sr-only">
          {tGeneration('empty')}
        </span>
      )}
    </span>
  );
}
