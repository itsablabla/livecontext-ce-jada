'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Sparkles } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { useChangelog } from '@/hooks/useChangelog';
import ChangelogMediaView from './ChangelogMediaView';

/**
 * Onboarding modals already queued in this session. The hook keeps the panel quiet until
 * onboarding is COMPLETE, which covers the flow itself; these flags cover the few seconds after
 * it, while the welcome-gift and suggested-apps modals are still waiting to show. Between them,
 * a first run is never interrupted by a release note.
 */
const ONBOARDING_FLAGS = ['lc_show_welcome_gift', 'lc_show_app_suggestions'];

/** Long enough for the app shell to settle, short enough to still read as part of arriving. */
const AUTO_OPEN_DELAY_MS = 1200;

/**
 * The in-app "What's new" panel: one entry, the newest, shown once per user.
 *
 * Mounted once in the app layout. It opens by itself when the running build ships an entry this
 * user has not acknowledged, and nothing else opens it: there is deliberately no entry point in
 * the app chrome. The archive of past entries lives on the public changelog page, which the panel
 * links to.
 *
 * The entry is acknowledged as soon as it is SHOWN, so "once per user" holds even for a reader who
 * closes the tab without dismissing it.
 */
export default function ChangelogModal() {
  const t = useTranslations('changelog');
  const { entry, isAvailable, decision, markSeen } = useChangelog();
  const [open, setOpen] = useState(false);
  // Read at mount, before onboarding consumes its own flags: by the time this panel would open,
  // the gift modal has already removed them from sessionStorage.
  const [onboardingInFlight] = useState(hasOnboardingFlag);

  // An account that never lacked what the entry announces is acknowledged without ever being
  // shown it. The ref keeps React's double-invoked effects (StrictMode) to one write.
  const sealedRef = useRef(false);
  useEffect(() => {
    if (decision !== 'seal' || sealedRef.current) return;
    sealedRef.current = true;
    markSeen();
  }, [decision, markSeen]);

  useEffect(() => {
    if (decision !== 'announce' || onboardingInFlight) return;
    const timer = window.setTimeout(() => setOpen(true), AUTO_OPEN_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [decision, onboardingInFlight]);

  // Acknowledged when it OPENS, not when it closes. "Shown once per user" has to mean shown, and
  // a user who reads the panel then closes the tab (or navigates away) writes nothing on the close
  // path - the panel would greet them again on the next load, forever. markSeen is idempotent and
  // no-ops once the key matches.
  useEffect(() => {
    if (open) markSeen();
  }, [open, markSeen]);

  const handleClose = useCallback(() => {
    setOpen(false);
    // Belt and braces: the open effect above has already acknowledged, and this is a no-op once
    // the key matches. It stays because closing is the moment the user is definitely done with
    // the entry, and an acknowledgement lost to a failed request on open gets a second chance.
    markSeen();
  }, [markSeen]);

  if (!open || !entry || !isAvailable) return null;

  return (
    <Dialog open={open} onOpenChange={(next) => !next && handleClose()}>
      <DialogContent className="max-w-lg gap-0 overflow-hidden border-theme bg-theme-primary p-0">
        <div className="border-b border-theme p-6 pb-5 pr-14">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-theme-tertiary">
              <Sparkles className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <DialogTitle className="text-lg font-semibold leading-6 text-theme-primary">
                {t('latest.title')}
              </DialogTitle>
              <p className="mt-1 text-sm leading-5 text-theme-secondary">{t('whatsNew')}</p>
            </div>
          </div>
        </div>

        <div className="space-y-4 p-6">
          {entry.media && <ChangelogMediaView media={entry.media} alt={t('latest.mediaAlt')} />}
          <DialogDescription className="text-sm leading-6 text-theme-secondary">
            {t('latest.body')}
          </DialogDescription>
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-theme p-4">
          {entry.learnMoreUrl ? (
            <a
              href={entry.learnMoreUrl}
              target="_blank"
              rel="noopener noreferrer"
              // Acknowledged on the way out too: the user has seen the entry, whatever they do
              // next with it.
              onClick={handleClose}
              className="text-sm text-theme-secondary underline underline-offset-4 hover:text-theme-primary"
            >
              {t('learnMore')}
            </a>
          ) : (
            <span />
          )}
          <Button onClick={handleClose} data-testid="changelog-dismiss">
            {t('dismiss')}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function hasOnboardingFlag(): boolean {
  try {
    return ONBOARDING_FLAGS.some((flag) => sessionStorage.getItem(flag) === '1');
  } catch {
    // Private mode / storage disabled: assume no onboarding rather than suppressing the panel
    // forever on a browser that cannot answer.
    return false;
  }
}
