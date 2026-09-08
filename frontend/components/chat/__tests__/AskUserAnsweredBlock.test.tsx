/**
 * @vitest-environment jsdom
 *
 * The transcript row for an ask_user call shows the questions it asked and the answers the
 * person gave; a `help` call (no questions) must not claim the row, or the expanded panel
 * renders blank instead of the raw result.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AskUserAnsweredBlock, parseAskUserQuestions } from '../AskUserAnsweredBlock';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

const askArgs = JSON.stringify({
  action: 'ask',
  questions: [
    { header: 'Tone', question: 'Which tone?', options: [{ label: 'Friendly' }, { label: 'Formal' }] },
    { header: 'Channels', question: 'Where?', options: [{ label: 'X' }, { label: 'Mail' }], multiSelect: true },
  ],
});

describe('parseAskUserQuestions', () => {
  it('reads the questions off an ask call and nothing off a help call, bad JSON or a non-list', () => {
    expect(parseAskUserQuestions(askArgs).map(q => q.header)).toEqual(['Tone', 'Channels']);
    expect(parseAskUserQuestions(JSON.stringify({ action: 'help' }))).toEqual([]);
    expect(parseAskUserQuestions('{"questions": not json')).toEqual([]);
    expect(parseAskUserQuestions(JSON.stringify({ questions: 'Tone?' }))).toEqual([]);
    expect(parseAskUserQuestions(JSON.stringify({ questions: [{ question: 'no header' }] }))).toEqual([]);
    expect(parseAskUserQuestions(undefined)).toEqual([]);
  });
});

describe('AskUserAnsweredBlock', () => {
  it('shows each question with the answer matched by header (labels and free text)', () => {
    render(<AskUserAnsweredBlock argumentsJson={askArgs} resultJson={JSON.stringify({
      status: 'answered',
      answers: [
        { header: 'tone', selected: ['Formal'] },
        { header: 'Channels', selected: ['X'], freeText: 'the blog' },
      ],
    })} />);

    const block = screen.getByTestId('ask-user-answered-block');
    expect(block).toHaveTextContent('Which tone?');
    expect(block).toHaveTextContent('Formal');
    expect(block).toHaveTextContent('X, the blog');
  });

  it('marks an open question as unanswered when the result is still pending', () => {
    render(<AskUserAnsweredBlock argumentsJson={askArgs} resultJson={JSON.stringify({ status: 'pending_user' })} />);

    expect(screen.getAllByText('noAnswer')).toHaveLength(2);
  });

  it('renders nothing for a call with no questions', () => {
    const { container } = render(<AskUserAnsweredBlock argumentsJson={JSON.stringify({ action: 'help' })} resultJson="{}" />);

    expect(container).toBeEmptyDOMElement();
  });
});
