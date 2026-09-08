'use client';

import { useTranslations } from 'next-intl';
import { HighlightedApps } from '@/components/chat/HighlightedApps';

/**
 * The applications that belong under the studio composer.
 *
 * <p><b>Why this is four lines and not a gallery.</b> The Home page already has a row of
 * application tiles, and that row is not a layout - it is a component that knows how to read the
 * marketplace and the reader's own library, how to render a live interface preview inside a tile,
 * which apps the reader already owns, how a cloud-linked self-hosted install reaches the cloud
 * catalogue, and what to install when the reader presses Install. A second gallery here would have
 * to answer all of that again, and would answer some of it differently.
 *
 * <p>So this narrows that row to the studio AXIS instead. Membership is the publisher's own
 * decision about their application, which is the only source that can be right: the guesses
 * available instead - "it renders video", "its workflow contains a generation step" - are wrong in
 * both directions, admitting an unrelated app that embeds a player and excluding a gallery whose
 * assets arrive from a table.
 *
 * <p>An axis and not a category, because a publication carries ONE category: modelled as a category,
 * a video studio would have had to stop being a Content app to become a studio one.
 *
 * <p>The row hides itself when the axis is empty, so a fresh install shows nothing here rather
 * than an empty shelf with a heading.
 */
export function StudioApps() {
  const t = useTranslations('studio');
  return (
    <HighlightedApps
      studioOnly
      heading={t('apps.title')}
      favoritesHeading={t('apps.favorites')}
    />
  );
}

export default StudioApps;
