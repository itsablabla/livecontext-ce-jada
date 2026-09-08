'use client';

import React, { useMemo } from 'react';
import { useTranslations } from 'next-intl';

export interface ParsedQuestion { header: string; question: string; options?: Array<{ label: string }> }
interface ParsedAnswer { header: string; selected?: string[]; freeText?: string | null }

/**
 * The questions an ask_user call asked, read off its persisted arguments. Empty for a
 * `help` call, for arguments that do not parse, and for anything that is not a list, so a
 * caller can decide to show this block only when it has something to show.
 */
export function parseAskUserQuestions(argumentsJson?: string): ParsedQuestion[] {
  if (!argumentsJson) return [];
  try {
    const args = JSON.parse(argumentsJson);
    return Array.isArray(args?.questions) ? args.questions.filter((q: ParsedQuestion) => q && typeof q.header === 'string') : [];
  } catch {
    return [];
  }
}

/**
 * Read-only view of an ask_user tool call in the transcript: the questions it asked (from
 * the call's arguments) and, when the result carries them, the answers the user gave. Used
 * by ActivityFeed for a persisted or completed ask_user tool row; the live card is
 * AskUserQuestionCard.
 */
export function AskUserAnsweredBlock({ argumentsJson, resultJson }: { argumentsJson?: string; resultJson?: string | null }) {
  const t = useTranslations('askUser');

  const questions = useMemo(() => parseAskUserQuestions(argumentsJson), [argumentsJson]);

  const { status, answers } = useMemo((): { status?: string; answers: ParsedAnswer[] } => {
    try {
      const res = resultJson ? JSON.parse(resultJson) : null;
      return { status: res?.status, answers: Array.isArray(res?.answers) ? res.answers : [] };
    } catch {
      return { status: undefined, answers: [] };
    }
  }, [resultJson]);

  const answerFor = (header: string) => answers.find(a => a.header?.toLowerCase() === header.toLowerCase());

  if (questions.length === 0) return null;

  return (
    <div className="space-y-2" data-testid="ask-user-answered-block">
      {questions.map(q => {
        const a = answerFor(q.header);
        const picks = a ? [...(a.selected ?? []), ...(a.freeText ? [a.freeText] : [])] : [];
        return (
          <div key={q.header} className="rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 p-2">
            <div className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{q.header}</div>
            <div className="text-sm text-slate-800 dark:text-slate-200">{q.question}</div>
            <div className="mt-1 text-sm">
              {picks.length > 0 ? (
                <span className="text-green-700 dark:text-green-400">{picks.join(', ')}</span>
              ) : (
                <span className="text-slate-500 dark:text-slate-400">
                  {status === 'answered' ? '-' : t('noAnswer')}
                </span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export default AskUserAnsweredBlock;
