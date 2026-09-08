import { integrationIconSrc, type PublicIntegration } from '@/lib/integrations/integrations';
import { isMonoDarkIconSlug } from '@/lib/credentials/monoIconSlugs';

/**
 * An integration's brand mark on the PUBLIC site.
 *
 * <p>A plain `<img>`, not `components/ui/service-icon.tsx`. That one is a client
 * component (it keeps an error flag in state) and renders through `next/image`;
 * this one has to render inside server components on pages that ship no
 * JavaScript for it, and 700 marks on the directory page is exactly the case
 * where the optimizer costs more than it saves on a 1 KB SVG that is already
 * served statically.
 *
 * <p>`logo-mono` is the landing chrome's class (defined in `landingChromeStyles`,
 * so it exists on every page that uses `LandingShell`): it flips a near-black
 * brand mark to white when the public theme is dark, where it would otherwise
 * disappear into the background.
 *
 * <p>`alt` is empty on purpose. Every call site puts the integration's name in
 * adjacent text, so a real alt would make a screen reader announce the name
 * twice, once as an image.
 */
export function IntegrationLogo({
  integration,
  size = 28,
  className = '',
}: {
  integration: PublicIntegration;
  size?: number;
  className?: string;
}) {
  return (
    <img
      src={integrationIconSrc(integration)}
      alt=""
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      className={`${isMonoDarkIconSlug(integration.iconSlug) ? 'logo-mono' : ''} ${className}`}
      style={{ width: size, height: size, objectFit: 'contain' }}
    />
  );
}

export default IntegrationLogo;
