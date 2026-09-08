'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { WelcomeTitle } from '@/app/shared/components';

/**
 * The studio's welcome line, rotating the way the chat home's does.
 *
 * <p><b>Why rotate at all.</b> The chat home rotates because one sentence cannot say what a chat is
 * for: the lines together describe a range. The studio has the same problem for the same reason -
 * "make an image" and "make a voice line" are one surface, and a single title names one of them.
 *
 * <p><b>Why its own component rather than a prop on the chat's.</b> The two share a rhythm, not a
 * dictionary: these lines are about producing an asset and the chat's are about automating work.
 * Threading a namespace through the chat component would let a studio line appear on the chat home
 * the first time somebody passed the wrong one, and the two are not interchangeable.
 *
 * <p>The first line is fixed rather than random, so the studio always opens on the same sentence and
 * the surface has a stable first impression; the rest follow in order.
 */

const TITLE_KEYS = ['title1', 'title2', 'title3', 'title4', 'title5'] as const;
const ROTATE_MS = 9000;
const FADE_MS = 280;

export interface StudioDynamicTitleProps {
  /** When true, rotation stops on the line currently displayed. */
  paused?: boolean;
  className?: string;
}

export function StudioDynamicTitle({ paused = false, className }: StudioDynamicTitleProps) {
  const t = useTranslations('studio.welcome');
  const [idx, setIdx] = useState(0);
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    if (paused) return;
    const swap = window.setInterval(() => {
      setVisible(false);
      window.setTimeout(() => {
        setIdx((i) => (i + 1) % TITLE_KEYS.length);
        setVisible(true);
      }, FADE_MS);
    }, ROTATE_MS);
    return () => window.clearInterval(swap);
  }, [paused]);

  return (
    <WelcomeTitle
      className={cn(
        'transition-opacity duration-300',
        visible ? 'opacity-100' : 'opacity-0',
        className,
      )}
    >
      {t(TITLE_KEYS[idx])}
    </WelcomeTitle>
  );
}

export default StudioDynamicTitle;
