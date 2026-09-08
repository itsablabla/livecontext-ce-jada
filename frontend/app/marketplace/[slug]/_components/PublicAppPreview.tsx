'use client';

import { useMemo, useState } from 'react';

import { InterfaceShadowPreview } from '@/app/workflows/builder/components/interface/InterfaceShadowPreview';
import { resolveInterfaceFormat } from '@/lib/interfaces/interfaceFormats';
import { resolveShowcaseContent } from '@/lib/interfaces/showcaseResolve';
import { useMeasuredBox } from '@/lib/interfaces/useFitScale';
import type { PublicShowcaseRender } from '@/lib/marketplace/publicPublications';

/**
 * The published application itself, shown rather than described.
 *
 * <p>It renders the frozen showcase the page fetched on the SERVER, so no token
 * is ever needed. The interface lives in an iframe sandboxed to `allow-scripts`
 * only: no same-origin, no forms, no top navigation, so nothing inside it can
 * submit or navigate.
 *
 * <p><b>Shown WHOLE, not cropped.</b> The obvious brick here is
 * `InterfaceThumbnail`, the one the marketplace cards use, and it is the wrong
 * one: it pins the page to its format viewport and injects
 * `body {'{ overflow: hidden }'}`, so anything the app draws below that height is
 * simply cut off. On a card that is right, the thumbnail is a glance. On this
 * page the application IS the subject, and a visitor who cannot see the bottom
 * of it has not seen the app. So the frame takes its height from the content
 * instead: `InterfaceShadowPreview` auto-sizes to the height the iframe reports
 * back, as long as no explicit height is handed to it, and the whole thing is
 * scaled to the page width.
 *
 * <p>That also settles the interaction question without a compromise. Growing
 * the frame means there is nothing to scroll INSIDE it, so the page can keep
 * pointer events off the preview: the visitor sees every pixel of the app and
 * still cannot drive it.
 *
 * <p><b>Publisher JS is deliberately kept.</b> An interface's `jsTemplate` is
 * what renders its lists and conditionals, so dropping it (`dropJs`) would show
 * a correct-looking but EMPTY application, which is worse than no preview. The
 * sandbox, not script removal, is what contains it: the same trade the
 * authenticated marketplace previews already make.
 */
export interface PublicAppPreviewProps {
  render: PublicShowcaseRender;
  className?: string;
}

/** What an interface with no declared format is authored against. */
const DEFAULT_VIEWPORT = { width: 1280, height: 800 } as const;

export default function PublicAppPreview({ render, className }: PublicAppPreviewProps) {
  const { effectiveHtml, resolvedData, mode } = useMemo(
    () => resolveShowcaseContent(render.items),
    [render.items],
  );

  // The shape the publisher authored for. Rendering a vertical app at the
  // page's own width would reflow it into a layout its author never saw.
  const viewport = resolveInterfaceFormat(render.format) ?? DEFAULT_VIEWPORT;

  const [boxRef, box] = useMeasuredBox<HTMLDivElement>();
  // Seeded with the format height, replaced by what the frame reports once it
  // has laid out. Both matter: the seed avoids a zero-height flash on first
  // paint, the report is what uncrops a long app.
  const [contentHeight, setContentHeight] = useState<number>(viewport.height);

  const scale = box.width > 0 ? box.width / viewport.width : 0;

  return (
    <div
      ref={boxRef}
      className={className}
      // The whole point: the visitor sees the app working, and cannot drive it.
      // Safe to keep now that nothing needs scrolling inside the frame.
      style={{ pointerEvents: 'none' }}
    >
      {scale > 0 && (
        <div style={{ height: contentHeight * scale, overflow: 'hidden' }}>
          <div
            style={{
              width: viewport.width,
              transform: `scale(${scale})`,
              transformOrigin: '0 0',
            }}
          >
            <InterfaceShadowPreview
              htmlTemplate={effectiveHtml || render.htmlTemplate}
              mode={mode}
              resolvedData={resolvedData}
              customCss={render.cssTemplate || undefined}
              jsTemplate={render.jsTemplate || undefined}
              // WIDTH ONLY. Passing a height here would switch the component
              // out of auto-height and re-introduce the crop this exists to fix.
              style={{ width: viewport.width }}
              // The outer transform already scales; leaving this on would stack
              // a second scaler inside the frame.
              autoFit={false}
              onSizeChange={(size) => {
                if (size.height > 0) setContentHeight(size.height);
              }}
              // No action mapping is forwarded, so the bridge has no trigger to
              // fire even if a click somehow reached the frame.
              mediaMuted
            />
          </div>
        </div>
      )}
    </div>
  );
}
