'use client';

import { useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/providers/smart-providers';
import { setLandingIntent, track } from '@/lib/analytics/analytics';

type Variant = 'primary' | 'secondary' | 'link';

interface SignInButtonProps {
  children: React.ReactNode;
  variant?: Variant;
  className?: string;
  returnTo?: string;
  /** Main-site origin. When set (docs subdomain), the button hands off to the
   *  apex app/auth with a full navigation instead of routing locally. */
  baseUrl?: string;
  /** Bounded analytics slug naming WHICH call to action this is (e.g.
   *  `hero_start_free`). Never a label: it is sent as-is to analytics. */
  cta?: string;
}

export default function SignInButton({
  children,
  variant = 'primary',
  className = '',
  returnTo = '/app/chat',
  baseUrl,
  cta,
}: SignInButtonProps) {
  const router = useRouter();
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth();

  const handleClick = useCallback(
    async (e: React.MouseEvent) => {
      e.preventDefault();
      // Before any navigation: the redirect unloads the page, so a later call
      // would never be flushed.
      track('landing_cta_clicked', {
        cta,
        return_to: returnTo,
        is_authenticated: isAuthenticated,
        off_host: Boolean(baseUrl),
      });
      if (cta) setLandingIntent('landing_cta', cta);
      if (baseUrl) {
        // Off the main host (e.g. the docs subdomain): hand off to the apex,
        // which owns the app + auth, with a full navigation.
        window.location.assign(`${baseUrl}${returnTo}`);
        return;
      }
      if (isLoading) return;
      if (isAuthenticated) {
        router.push(returnTo);
        return;
      }
      await loginWithRedirect({ appState: { returnTo } });
    },
    [baseUrl, cta, isAuthenticated, isLoading, loginWithRedirect, returnTo, router]
  );

  const variantStyle: React.CSSProperties =
    variant === 'primary'
      ? { background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }
      : variant === 'secondary'
        ? { border: '1px solid var(--border-color)', color: 'var(--text-primary)' }
        : { color: 'var(--text-secondary)' };

  return (
    <a
      href={baseUrl ? `${baseUrl}${returnTo}` : returnTo}
      onClick={handleClick}
      className={className}
      style={variantStyle}
      aria-busy={isLoading || undefined}
    >
      {children}
    </a>
  );
}
