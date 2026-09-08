'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { AlertCircle, CheckCircle } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { getClientLocale } from '@/lib/utils/locale';
import {
  WEEKDAYS,
  clamp,
  parseDailyCron,
  parseMonthlyCron,
  parseWeeklyCron,
} from '@/lib/schedule/cronFrequencies';

/**
 * The controls that turn a frequency choice into a cron string, and the panel that says
 * what the resulting cron means.
 *
 * <p>They were written inside the workflow builder's schedule inspector. The agenda now
 * offers the same composition when a workflow is created from an empty calendar slot, and
 * the pickers are the half a user actually operates: a second implementation would be two
 * products disagreeing about what "weekly" lets you pick (the refusal to deselect the last
 * weekday below is exactly the kind of rule a re-implementation drops), and about what the
 * next runs are.
 *
 * <p>They take their translations as a prop rather than reading a namespace, so the caller
 * decides which one they belong to - today both callers pass
 * `workflowBuilder.inspector.scheduleTrigger`, which is where the strings already live.
 *
 * <p>Deliberately unchanged in this move, including the slate palette: restyling them onto
 * the theme tokens would silently repaint the builder inspector, which is not what a shared
 * module is for.
 */

export interface ValidateCronResponse {
  valid: boolean;
  description?: string;
  nextExecutions?: string[];
}

// -----------------------------------------------------------------------------
// Sub-pickers
// -----------------------------------------------------------------------------

export interface PickerProps {
  cron: string;
  disabled: boolean;
  t: ReturnType<typeof useTranslations>;
}

export function DailyCustomPicker({ cron, disabled, onChange, t }: PickerProps & {
  onChange: (hour: number, minute: number) => void;
}) {
  const { hour, minute } = React.useMemo(() => parseDailyCron(cron), [cron]);
  return (
    <div className="space-y-2 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 p-3">
      <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeLabel')}</label>
      <TimePicker hour={hour} minute={minute} disabled={disabled} onChange={onChange} />
    </div>
  );
}

export function WeeklyCustomPicker({ cron, disabled, onChange, t }: PickerProps & {
  onChange: (hour: number, minute: number, days: string[]) => void;
}) {
  const { hour, minute, days } = React.useMemo(() => parseWeeklyCron(cron), [cron]);
  // Refuse to deselect the last active day - otherwise the cron silently falls
  // back to Monday (per buildWeeklyCron), which would be a confusing UX where
  // the button looks unselected but the schedule still fires Mondays.
  const toggleDay = (day: string) => {
    const isSelected = days.includes(day);
    if (isSelected && days.length === 1) return;
    const next = isSelected ? days.filter(d => d !== day) : [...days, day];
    onChange(hour, minute, next);
  };
  return (
    <div className="space-y-3 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 p-3">
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('daysLabel')}</label>
        <div className="flex flex-wrap gap-1.5">
          {WEEKDAYS.map((wd) => {
            const selected = days.includes(wd.value);
            const isLastSelected = selected && days.length === 1;
            const buttonDisabled = disabled || isLastSelected;
            return (
              <button
                key={wd.value}
                type="button"
                disabled={buttonDisabled}
                onClick={() => toggleDay(wd.value)}
                title={isLastSelected ? t('atLeastOneDay') : undefined}
                className={`px-2.5 py-1 rounded text-xs font-medium transition-colors ${
                  selected
                    ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300 border border-blue-300 dark:border-blue-700'
                    : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-600 hover:bg-slate-200 dark:hover:bg-slate-600'
                } ${buttonDisabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
              >
                {t(wd.labelKey)}
              </button>
            );
          })}
        </div>
      </div>
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeLabel')}</label>
        <TimePicker
          hour={hour}
          minute={minute}
          disabled={disabled}
          onChange={(h, m) => onChange(h, m, days)}
        />
      </div>
    </div>
  );
}

export function MonthlyCustomPicker({ cron, disabled, onChange, t }: PickerProps & {
  onChange: (hour: number, minute: number, dayOfMonth: number) => void;
}) {
  const { hour, minute, dayOfMonth } = React.useMemo(() => parseMonthlyCron(cron), [cron]);
  return (
    <div className="space-y-3 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 p-3">
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('dayOfMonthLabel')}</label>
        <Input
          type="number"
          min={1}
          max={31}
          value={dayOfMonth}
          disabled={disabled}
          onChange={(e) => {
            const next = clamp(parseInt(e.target.value, 10), 1, 31);
            onChange(hour, minute, next);
          }}
          className="w-24"
        />
      </div>
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeLabel')}</label>
        <TimePicker
          hour={hour}
          minute={minute}
          disabled={disabled}
          onChange={(h, m) => onChange(h, m, dayOfMonth)}
        />
      </div>
    </div>
  );
}

export function TimePicker({
  hour, minute, disabled, onChange,
}: { hour: number; minute: number; disabled: boolean; onChange: (h: number, m: number) => void }) {
  return (
    <div className="flex items-center gap-1.5">
      <Input
        type="number"
        min={0}
        max={23}
        value={hour}
        disabled={disabled}
        onChange={(e) => onChange(clamp(parseInt(e.target.value, 10), 0, 23), minute)}
        className="w-16 text-center"
      />
      <span className="text-slate-400 font-medium">:</span>
      <Input
        type="number"
        min={0}
        max={59}
        value={minute.toString().padStart(2, '0')}
        disabled={disabled}
        onChange={(e) => onChange(hour, clamp(parseInt(e.target.value, 10), 0, 59))}
        className="w-16 text-center"
      />
    </div>
  );
}

// -----------------------------------------------------------------------------
// Validation feedback panel (description + next-runs preview from backend)
// -----------------------------------------------------------------------------

export function ValidationFeedback({
  validation, isValidating, timezone, cronIsNonEmpty, hasPersistedSchedule, t,
}: {
  validation: ValidateCronResponse | null;
  isValidating: boolean;
  timezone: string;
  cronIsNonEmpty: boolean;
  hasPersistedSchedule: boolean;
  t: ReturnType<typeof useTranslations>;
}) {
  // While an answer is in flight the panel says so, even when it already holds one. Guarded
  // on `!validation` it only ever spoke on a first load, so every later edit left the
  // PREVIOUS cron's green description and next-run list sitting under the new selection -
  // a confident, wrong answer, which is the one thing this panel must never give.
  if (isValidating) {
    return (
      <div className="rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 p-3 text-xs text-slate-400 italic">
        {t('validating')}
      </div>
    );
  }
  if (!validation) return null;

  if (!validation.valid) {
    // Surface state divergence: a non-empty INVALID cron is silently rejected by the
    // backend PUT, so the typed value is NOT what the schedule will fire. The previous
    // valid cron (persisted server-side) stays active. Without this hint, the red panel
    // alone could read as "we'll save it anyway", which would be a serious surprise the
    // first time the schedule fires at the OLD cadence.
    const showNotSavedHint = cronIsNonEmpty && hasPersistedSchedule;
    return (
      <div className="rounded-md border border-red-200 dark:border-red-900/50 bg-red-50 dark:bg-red-900/20 p-3">
        <div className="flex items-start gap-2">
          <AlertCircle className="h-3.5 w-3.5 text-red-500 mt-0.5 flex-shrink-0" />
          <div className="space-y-1">
            <p className="text-xs font-medium text-red-700 dark:text-red-300">{t('invalidCron')}</p>
            <p className="text-xs text-red-600 dark:text-red-400">{t('invalidCronHelp')}</p>
            {showNotSavedHint && (
              <p className="text-xs text-red-600 dark:text-red-400 italic pt-1 border-t border-red-200 dark:border-red-900/50 mt-1">
                {t('notSavedHint')}
              </p>
            )}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 p-3 space-y-2">
      <div className="flex items-start gap-2">
        <CheckCircle className="h-3.5 w-3.5 text-green-500 mt-0.5 flex-shrink-0" />
        <p className="text-xs font-medium text-slate-700 dark:text-slate-200">
          {validation.description || t('valid')}
        </p>
      </div>
      {validation.nextExecutions && validation.nextExecutions.length > 0 && (
        <div className="space-y-1 pl-5">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">
            {t('nextRunsLabel', { timezone })}
          </p>
          <ul className="space-y-0.5">
            {validation.nextExecutions.map((iso, i) => (
              <li key={i} className="text-xs text-slate-600 dark:text-slate-300 font-mono">
                {formatNextRun(iso, timezone)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export function formatNextRun(iso: string, timezone: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleString(getClientLocale(), {
      timeZone: timezone,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
