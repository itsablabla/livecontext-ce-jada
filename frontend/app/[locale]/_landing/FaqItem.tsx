'use client';

import { track } from '@/lib/analytics/analytics';

/**
 * One native `<details>` FAQ entry. A client island only because opening it is
 * tracked (`onToggle`); the landing page is a server component. Only the
 * bounded index leaves the browser, never the question text.
 */
export default function FaqItem({
  faqIndex,
  className,
  style,
  children,
}: {
  faqIndex: number;
  className?: string;
  style?: React.CSSProperties;
  children: React.ReactNode;
}) {
  return (
    <details
      className={className}
      style={style}
      onToggle={(e) => {
        if (e.currentTarget.open) track('landing_faq_opened', { faq_index: faqIndex });
      }}
    >
      {children}
    </details>
  );
}
