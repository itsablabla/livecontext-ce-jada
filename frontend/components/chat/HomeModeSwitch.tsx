'use client';

import * as React from 'react';
import { Clapperboard, MessageSquare } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { usePathname, useRouter } from '@/i18n/navigation';
import { ToggleGroup } from '@/components/ui/toggle-group';

/**
 * The two things the home page can be: a chat, or the studio.
 *
 * <p><b>Why a switch and not a link.</b> These are not two features one of which lives in a menu:
 * they are the same act - saying what you want - answered by two different machines. A reader
 * arriving at the app is choosing between "talk to it" and "make something", and a switch is what
 * that choice looks like. It sits above the composer because it decides what the composer IS.
 *
 * <p><b>It only appears on the home page.</b> Once a conversation exists, its kind is fixed - the
 * server refuses a change - so a switch inside a thread would offer something that cannot happen.
 * Leaving a studio thread means starting a new conversation, which is what pressing Chat here does.
 *
 * <p>Built on the app's own {@code ToggleGroup} rather than two buttons: it carries the pill
 * styling, the pressed state and, given a label, the radio-group semantics a screen reader needs to
 * announce that picking one replaces the other.
 */

export type HomeMode = 'chat' | 'studio';

export interface HomeModeSwitchProps {
  /** Which side is active. The caller knows, because it knows which surface it is rendering. */
  mode: HomeMode;
  /** Called before navigating, so the caller can stop a rotating title or a chip cycle. */
  onInteract?: () => void;
  /**
   * Icon-only, and sized to sit in a composer's button row.
   *
   * <p>The labelled pill was built to stand alone above the composer, where it had a line to
   * itself. Inside the row it is one control among several, and a two-word pill there is twice the
   * height of its neighbours and pushes the parameter toggles off a narrow row. Compact matches the
   * 36px the row's other buttons occupy; the words move to the accessible name, which is where a
   * screen reader was reading them from anyway.
   */
  compact?: boolean;
  className?: string;
}

export function HomeModeSwitch({ mode, onInteract, compact = false, className }: HomeModeSwitchProps) {
  const t = useTranslations('studio.modeSwitch');
  const router = useRouter();
  const pathname = usePathname();

  const handleChange = React.useCallback((next: string) => {
    onInteract?.();
    if (next === mode) return;
    const target = next === 'studio' ? '/app/studio' : '/app';
    // Nothing to preserve across the switch: neither side has a draft yet (the switch is only on
    // the home page) and the two composers accept different things, so carrying text over would
    // hand the studio a sentence meant for a chat model.
    if (pathname !== target) router.push(target);
  }, [mode, onInteract, pathname, router]);

  // Compact keeps the words for a screen reader and for the tooltip, and drops them from the
  // button: an icon-only control with no accessible name is a control a reader cannot identify.
  const optionLabel = (text: string) => (compact ? <span className="sr-only">{text}</span> : text);
  // `ring-offset-2` is the mini padding: without it the focus ring sits flush on the button's own
  // edge inside a container of the same radius, so the two curves touch and read as one thick
  // border rather than as a ring around a control.
  //
  // The inner radius is the outer one MINUS the 2px inset (16 - 2 = 14). Reusing the outer value
  // inside is the usual slip: the two curves then run at different distances from the same corner
  // and the inset reads as a manufacturing error rather than as a frame.
  const optionClass = compact
    ? 'h-8 w-8 px-0 rounded-xl focus-visible:ring-offset-2 focus-visible:ring-offset-theme-primary'
    : undefined;

  return (
    <ToggleGroup
      value={mode}
      onValueChange={handleChange}
      variant="pill"
      // No border in the compact form: the row's other controls are borderless icon buttons, and a
      // framed box around two of them reads as a group fenced off from the rest. The ground stays,
      // which is what still says the two icons are one control.
      hasBorder={!compact}
      // ONE radius for the container and the buttons alike. They differed - an `xl` frame around
      // `lg` buttons - and the mismatch shows up worst on keyboard focus, where the ring traces the
      // button's curve inside a container tracing another. `h-9` is border-box, so the outer height
      // lands exactly on the 36px the row's other buttons occupy rather than adding the inset to it.
      // The buttons carry the app's own `rounded-xl` (12px), the same curve every other control in
      // this row has, and the frame sits 2px outside them at 14px so the two stay concentric. The
      // 2px is also the space the focus ring needs: `ring-offset-2` draws it in the gap instead of
      // on the frame, where the two curves touched and read as one thick border.
      className={compact ? `h-9 gap-0.5 p-0.5 rounded-[14px] ${className ?? ''}` : className}
      ariaLabel={t('label')}
      options={[
        {
          value: 'chat',
          label: optionLabel(t('chat')),
          icon: <MessageSquare className="h-4 w-4" />,
          className: optionClass,
        },
        {
          value: 'studio',
          label: optionLabel(t('studio')),
          // A clapperboard: the studio makes takes - an image, a clip, a voice line - and the
          // board is what names one before it is shot. A wand would say "magic"; this says work.
          icon: <Clapperboard className="h-4 w-4" />,
          className: optionClass,
        },
      ]}
    />
  );
}

export default HomeModeSwitch;
