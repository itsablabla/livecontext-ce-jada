// @vitest-environment jsdom
/**
 * The question card against the REAL dictionaries, in two languages.
 *
 * <p>The card's own suite stubs the translator (t(key) returns the key), so it certifies that a
 * key is ASKED for, never that it EXISTS. That is how `askUser.answerResumes` shipped in the
 * wrong namespace on the first pass: the stub returned the key, the assertion matched the key,
 * and the card would have shown `askUser.answerResumes` to a person in its most common state
 * (a card whose turn is over). next-intl prints the key path when a message is missing, without
 * throwing. So the assertions here are WORDS a reader would recognise, in a language where they
 * differ from the key.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import { AskUserQuestionCard } from '../AskUserQuestionCard';

const questions = [
  { header: 'Tone', question: 'Which tone?', options: [{ label: 'Friendly' }, { label: 'Formal' }], multiSelect: false },
  { header: 'Channels', question: 'Where?', options: [{ label: 'X' }, { label: 'LinkedIn' }], multiSelect: true },
];

function renderIn(locale: 'en' | 'fr', blocking: boolean) {
  const messages = locale === 'en' ? enMessages : frMessages;
  return render(
    <NextIntlClientProvider locale={locale} messages={messages}>
      <AskUserQuestionCard
        conversationId="conv-1"
        pendingQuestion={{ toolCallId: 'call-1', blocking, gateKey: blocking ? 'call-1:ask' : undefined, timestamp: 1, questions }}
      />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe('AskUserQuestionCard with the real dictionaries', () => {
  it('en: a card whose turn is over says so in words, and counts the steps', () => {
    renderIn('en', false);
    expect(screen.getByTestId('ask-user-resumes')).toHaveTextContent('Your answer will start it again');
    expect(screen.getByText('Question 1 of 2')).toBeInTheDocument();
    expect(screen.getByTestId('ask-user-next')).toHaveTextContent('Next');
    expect(screen.queryByText(/askUser\./)).not.toBeInTheDocument();
  });

  it('en: a held card says the assistant is waiting', () => {
    renderIn('en', true);
    expect(screen.getByTestId('ask-user-waiting')).toHaveTextContent('waiting for your answer');
  });

  it('fr: every string of the card is French, never a key path', () => {
    renderIn('fr', false);
    expect(screen.getByTestId('ask-user-resumes')).toHaveTextContent('Votre r');
    expect(screen.getByText('Question 1 sur 2')).toBeInTheDocument();
    expect(screen.getByTestId('ask-user-next')).toHaveTextContent('Suivant');
    expect(screen.queryByText(/askUser\./)).not.toBeInTheDocument();
  });
});
