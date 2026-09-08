'use client';

import * as React from 'react';
import { Table2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { openPlanComparison } from '@/lib/billing/plan-comparison-open';
import { cn } from '@/lib/utils';

/**
 * "Compare plans", the button that opens the comparison.
 *
 * <p><b>Two callers, and that is the design.</b> The pricing page and the
 * landing's pricing section. It used to be the shared affordance of a dozen
 * upsell surfaces, which is how a full-screen matrix ended up one click away
 * everywhere; it stays a component rather than two inline buttons so the two
 * remaining surfaces cannot drift apart.
 *
 * <p><b>It is the app's primary button.</b> It used to be a deliberately small
 * text link, on the reasoning that each host already carries its own primary
 * action and that a second prominent button would compete with it. In practice
 * the affordance was missed: a grey 13px line under a black button does not read
 * as the answer to "what do the other plans give". So it uses the same `default`
 * button as Sign in, Top up and Checkout, which is what "a thing you can press
 * here" looks like everywhere else in the product.
 *
 * <p><b>It is a button, never a link.</b> It opens a surface on top and hands
 * the reader back exactly where they were, rather than navigating away from the
 * page they are reading prices on.
 *
 * <p><b>The props have no caller today.</b> `variant='inline'` existed for a
 * badge that rendered it inside a sentence, and `highlightPlan`/`highlightRow`
 * for hosts that knew which row and column answered their own restriction. Both
 * are kept for an entry point added back later, and both are unused as it
 * stands: neither current caller passes anything.
 */

export interface ComparePlansLinkProps {
  /**
   * A backend plan code to mark in the table, when the caller knows which plan
   * would lift the restriction ("this node needs STARTER").
   */
  highlightPlan?: string | null;
  /**
   * A row to bring into view: a dimension id (`storage`, `users`, `workspaces`,
   * `credits`, `concurrent`, `variables`, `logs`, `nodes`) or a feature key
   * (`sso`, `apiAccess`...). Unknown ids highlight nothing.
   */
  highlightRow?: string | null;
  /** 'inline' for a text link inside a paragraph, 'standalone' for its own line. */
  variant?: 'inline' | 'standalone';
  className?: string;
}

export default function ComparePlansLink({
  highlightPlan = null,
  highlightRow = null,
  variant = 'standalone',
  className,
}: ComparePlansLinkProps) {
  const t = useTranslations('pricing.compare');
  const open = () => openPlanComparison({ highlightPlan, highlightRow });

  if (variant === 'inline') {
    return (
      <button
        type="button"
        data-testid="compare-plans-link"
        onClick={open}
        className={cn(
          'inline-flex items-center gap-1.5 text-sm font-medium text-theme-secondary underline underline-offset-2 transition-colors hover:text-theme-primary',
          className
        )}
      >
        {t('open')}
      </button>
    );
  }

  return (
    <Button
      type="button"
      variant="default"
      data-testid="compare-plans-link"
      onClick={open}
      className={cn('shrink-0', className)}
    >
      <Table2 className="h-4 w-4 shrink-0" aria-hidden />
      {t('open')}
    </Button>
  );
}
