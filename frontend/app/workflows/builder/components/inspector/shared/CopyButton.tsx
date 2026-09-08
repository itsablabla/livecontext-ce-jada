/**
 * CopyButton - copies a run value to the clipboard.
 *
 * Same icon-button chrome as the file view/download buttons already used inside
 * the run data tree, so a row that gains a copy affordance does not look like a
 * different component. Shows a check for a moment after a successful copy; on a
 * denied clipboard (an insecure origin, a browser that refuses without a user
 * gesture chain) it stays silent rather than throwing into the tree.
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Check, Copy } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { toClipboardText } from '../outputs/runValueUtils';

interface CopyButtonProps {
  /** Raw value to copy - strings go verbatim, everything else as pretty JSON. */
  value: unknown;
  /** Accessible label / tooltip. Defaults to the generic "Copy value". */
  title?: string;
  className?: string;
  /** Icon size class pair, defaults to the tree's 3.5 icons. */
  iconClassName?: string;
  'data-testid'?: string;
}

export function CopyButton({
  value,
  title,
  className,
  iconClassName = 'h-3.5 w-3.5',
  'data-testid': testId,
}: CopyButtonProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const [copied, setCopied] = React.useState(false);
  const timerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  React.useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const handleCopy = React.useCallback(
    async (e: React.MouseEvent) => {
      // The tree rows around this button toggle expansion on click and can be
      // draggable - copying must not also open the node.
      e.stopPropagation();
      e.preventDefault();
      try {
        await navigator.clipboard.writeText(toClipboardText(value));
        setCopied(true);
        if (timerRef.current) clearTimeout(timerRef.current);
        timerRef.current = setTimeout(() => setCopied(false), 1500);
      } catch {
        // Clipboard refused (insecure origin / permission). Nothing to recover:
        // the value stays selectable in the tree.
      }
    },
    [value],
  );

  const label = title ?? t('copyValue');

  return (
    <button
      type="button"
      onClick={handleCopy}
      onMouseDown={(e) => e.stopPropagation()}
      draggable={false}
      title={copied ? t('copied') : label}
      aria-label={label}
      data-testid={testId}
      className={clsx(
        'p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors',
        className,
      )}
    >
      {copied ? (
        <Check className={clsx(iconClassName, 'text-emerald-600 dark:text-emerald-400')} />
      ) : (
        <Copy className={iconClassName} />
      )}
    </button>
  );
}
