// @vitest-environment jsdom
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ProviderCard from '../components/ProviderCard';
import type { LlmProviderStatus } from '@/lib/api/orchestrator/types';

afterEach(cleanup);
const definition = {
  providerName: 'openai', integrationName: 'llm_openai', displayName: 'OpenAI',
  docsUrl: 'https://example.com', placeholder: 'API key',
  endpointPlaceholder: 'https://example.com/v1/chat/completions',
};

function setup(endpointUrl = '') {
  const onSave = vi.fn().mockResolvedValue(undefined);
  const onDelete = vi.fn().mockResolvedValue(undefined);
  render(<ProviderCard definition={definition}
    status={{ source: 'database', hasDbKey: true, endpointUrl } as LlmProviderStatus}
    onSave={onSave} onDelete={onDelete} t={(key) => key} />);
  return { onSave, onDelete, input: screen.getByPlaceholderText(definition.endpointPlaceholder) };
}

describe('ProviderCard endpoint configuration', () => {
  it('renders the saved endpoint without exposing the saved secret', () => {
    const { input } = setup('https://gateway.example/v1/chat/completions');
    expect(input).toHaveValue('https://gateway.example/v1/chat/completions');
    expect(screen.getByPlaceholderText('updateKey')).toHaveValue('');
  });

  it('saves endpoint-only changes without replacing the API key', async () => {
    const { input, onSave } = setup();
    fireEvent.change(input, { target: { value: 'https://gateway.example/v1/chat/completions' } });
    fireEvent.click(screen.getByRole('button', { name: 'save' }));
    await waitFor(() => expect(onSave).toHaveBeenCalledWith(
      'llm_openai', '', 'https://gateway.example/v1/chat/completions'));
  });

  it('allows clearing an endpoint to restore the provider default', async () => {
    const { input, onSave } = setup('https://gateway.example/v1/chat/completions');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'save' }));
    await waitFor(() => expect(onSave).toHaveBeenCalledWith('llm_openai', '', ''));
  });

  it('does not enable Save when nothing changed', () => {
    setup('https://gateway.example/v1/chat/completions');
    expect(screen.getByRole('button', { name: 'save' })).toBeDisabled();
  });
});
