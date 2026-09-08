'use client';

import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { setLandingIntent, track } from '@/lib/analytics/analytics';

/**
 * The "Self-host" GitHub link of the landing. A client island only because the
 * click is tracked: the landing page itself is a server component and cannot
 * carry an onClick. The anchor still navigates normally (no preventDefault);
 * the icon + label come from the page as children so no chrome module is
 * pulled into the client bundle.
 */
export default function SelfHostLink({
  section,
  className,
  style,
  children,
}: {
  /** Bounded slug of the section hosting the link (`hero`, `final_cta`). */
  section: string;
  className?: string;
  style?: React.CSSProperties;
  children: React.ReactNode;
}) {
  return (
    <a
      href={SELF_HOSTED_GITHUB_URL}
      target="_blank"
      rel="noopener noreferrer"
      className={className}
      style={style}
      onClick={() => {
        track('landing_cta_clicked', { cta: 'self_host_github', section });
        setLandingIntent('landing_cta', 'self_host_github');
      }}
    >
      {children}
    </a>
  );
}
