'use client';

import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import { X, Pin } from 'lucide-react';
import { MEMORY_FIELD_LIMITS } from '@/lib/api/orchestrator/memory.service';
import type { Memory, MemoryType, MemoryWriteRequest } from '@/lib/api/orchestrator/memory.service';

const TYPES: MemoryType[] = ['user', 'feedback', 'project', 'reference'];

interface MemoryEditorModalProps {
  /** null = create a new entry. */
  memory: Memory | null;
  onClose: () => void;
  onSave: (payload: MemoryWriteRequest) => Promise<void>;
}

/**
 * Create or edit one memory by hand.
 *
 * The form deliberately separates the summary from the body, and says why on
 * screen: the summary is the line every agent in the workspace carries in its
 * context on every run, and the body is only read when an agent decides the
 * summary looks relevant. A person who does not know that writes summaries like
 * "notes about the deployment", which cost the same and recall nothing.
 */
export function MemoryEditorModal({ memory, onClose, onSave }: MemoryEditorModalProps) {
  const t = useTranslations('memory');
  // Initialised from the prop ONCE, not synced through an effect. The parent
  // remounts this component with key={memory?.id ?? 'new'}, so opening a
  // different entry gives a fresh form. Copying props into state in an effect
  // instead would clobber whatever the person had typed on any unrelated
  // re-render of the parent.
  const [title, setTitle] = useState(memory?.title ?? '');
  const [summary, setSummary] = useState(memory?.summary ?? '');
  const [content, setContent] = useState(memory?.content ?? '');
  const [type, setType] = useState<MemoryType>(memory?.type ?? 'project');
  const [pinned, setPinned] = useState(memory?.pinned ?? false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave({ title, summary, content, type, pinned, slug: memory?.slug });
      onClose();
    } catch (e) {
      // The backend refuses text that reads as an instruction and explains how to
      // rewrite it. That message is the whole value of the refusal, so it is shown
      // verbatim rather than replaced with a generic failure.
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  // Escape closes it. A form this size is easy to open by accident from the row
  // actions, and a dialog that can only be dismissed by finding its X is the one
  // people work around by reloading the page, losing anything else in flight.
  // Not while SAVING: closing mid-request would leave the person unsure whether
  // the write landed.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !saving) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose, saving]);

  const canSave = title.trim().length > 0 && summary.trim().length > 0 && !saving;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      {/* role/aria-modal to match BulkDeleteModal, the platform's other confirm
          dialog: without them a screen reader announces this as ordinary page
          content and reads the list behind it as if it were still reachable. */}
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="memory-editor-title"
        className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-lg bg-theme-primary border border-theme shadow-xl"
      >
        <div className="flex items-center justify-between border-b border-theme px-5 py-3">
          <h2 id="memory-editor-title" className="text-base font-medium text-theme-primary">
            {memory ? t('editTitle') : t('createTitle')}
          </h2>
          <button type="button" onClick={onClose} className="text-theme-muted hover:text-theme-primary" aria-label={t('close')}>
            <X className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="space-y-4 px-5 py-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-theme-primary" htmlFor="memory-title">
              {t('fieldTitle')}
            </label>
            <Input
              id="memory-title"
              value={title}
              maxLength={MEMORY_FIELD_LIMITS.title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t('fieldTitlePlaceholder')}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-theme-primary" htmlFor="memory-summary">
              {t('fieldSummary')}
            </label>
            <Input
              id="memory-summary"
              value={summary}
              maxLength={MEMORY_FIELD_LIMITS.summary}
              onChange={(e) => setSummary(e.target.value)}
              placeholder={t('fieldSummaryPlaceholder')}
            />
            <p className="mt-1 text-xs text-theme-muted">
              {t('fieldSummaryHint', { count: summary.length, max: MEMORY_FIELD_LIMITS.summary })}
            </p>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-theme-primary" htmlFor="memory-content">
              {t('fieldContent')}
            </label>
            <textarea
              id="memory-content"
              value={content}
              maxLength={MEMORY_FIELD_LIMITS.content}
              onChange={(e) => setContent(e.target.value)}
              rows={8}
              className="w-full rounded-md border border-theme bg-theme-secondary px-3 py-2 text-sm text-theme-primary"
              placeholder={t('fieldContentPlaceholder')}
            />
            <p className="mt-1 text-xs text-theme-muted">{t('fieldContentHint')}</p>
          </div>

          <div className="flex flex-wrap items-center gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-theme-primary" htmlFor="memory-type">
                {t('fieldType')}
              </label>
              <select
                id="memory-type"
                value={type}
                onChange={(e) => setType(e.target.value as MemoryType)}
                className="rounded-md border border-theme bg-theme-secondary px-3 py-2 text-sm text-theme-primary"
              >
                {TYPES.map((value) => (
                  <option key={value} value={value}>{t(`type.${value}`)}</option>
                ))}
              </select>
            </div>

            <label className="mt-5 flex items-center gap-2 text-sm text-theme-primary">
              <input type="checkbox" checked={pinned} onChange={(e) => setPinned(e.target.checked)} />
              <Pin className="h-3.5 w-3.5" />
              {t('fieldPinned')}
            </label>
          </div>
          <p className="text-xs text-theme-muted">{t('fieldPinnedHint')}</p>

          {error && (
            <div className="rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-400">
              {error}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-theme px-5 py-3">
          <Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
          <Button onClick={handleSave} disabled={!canSave}>
            {saving ? t('saving') : t('save')}
          </Button>
        </div>
      </div>
    </div>
  );
}
