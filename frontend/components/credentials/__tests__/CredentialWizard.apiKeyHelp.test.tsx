// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// An api_key credential is not always "paste the token". Higgsfield's key is a
// PAIR that has to be entered as KEY_ID:KEY_SECRET, and entering only the id
// returns 401 Invalid credentials with nothing to explain it.
//
// That sentence had nowhere to go. The seed's auth[].notes was copied into
// credential metadata and read by nobody, and the api_key branch renders a fixed
// label and placeholder from i18n. The remaining channel, the dialog's own
// description, is clamped to TWO LINES with the rest in a title attribute, so a
// sentence past the opening one is never seen. So the field now carries the help
// text: the importer builds it from auth[].notes, and this branch renders it.
//
// The clamp is why this test exists at its own file: the first fix put the
// sentence in the description and looked right in the JSON while being invisible
// on screen.

let __TEMPLATE: Record<string, unknown>;

vi.mock('@/lib/api/orchestrator', async () => ({
  orchestratorApi: {
    getCredentialTemplateByName: vi.fn(() => Promise.resolve(__TEMPLATE)),
    getCredentialTemplates: vi.fn(() => Promise.resolve({ credentials: [__TEMPLATE] })),
    getPlatformCredentialsAvailability: vi.fn(() => Promise.resolve({ available: false, showUnverifiedAppWarning: false })),
    getCredentialVariants: vi.fn(() => Promise.resolve([])),
    createCredential: vi.fn(() => Promise.resolve({ id: 1 })),
  },
}));

import { CredentialWizard } from '../CredentialWizard';

const messages = {
  credentials: {
    wizard: {
      title: 'Connect', saving: 'Saving...', save: 'Save', close: 'Close', done: 'Done',
      username: 'Username', usernamePlaceholder: 'Enter username',
      password: 'Password', passwordPlaceholder: 'Enter password',
      apiKey: 'API Key', apiKeyPlaceholder: 'Enter API key',
      bearerToken: 'Token', bearerPlaceholder: 'Enter token',
      errors: {
        basicRequired: 'Username and password are required',
        apiKeyRequired: 'API key required',
        bearerRequired: 'Token required',
        customFieldRequired: '{field} is required',
        saveFailed: 'Save failed',
      },
    },
    configureDialog: {
      cancel: 'Cancel', optional: 'optional', credential: 'Credential', connect: 'Connect', connecting: 'Connecting',
      credentialName: 'Credential Name', credentialNamePlaceholder: 'e.g. {name}',
    },
  },
};

function renderWizard(iconSlug: string, serviceName: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard requirements={[{ iconSlug, serviceName }]} open onOpenChange={() => {}} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

const HELP =
  'A Higgsfield credential is a PAIR: a key id and a key secret. Paste them as KEY_ID:KEY_SECRET.';

describe('CredentialWizard - api_key field help text', () => {
  beforeEach(() => vi.clearAllMocks());

  it("renders what the API says about its own key, next to the input the user is about to fill", async () => {
    __TEMPLATE = {
      id: 't-higgsfield', credential_name: 'higgsfield', display_name: 'Higgsfield',
      icon_slug: 'higgsfield', auth_type: 'api_key', source: 'catalog',
      description: 'Higgsfield is a single API in front of many generative models.',
      properties: [
        { name: 'api_key', displayName: 'Api Key', type: 'password', required: true, description: HELP },
      ],
    };
    renderWizard('higgsfield', 'Higgsfield');

    expect(await screen.findByText('API Key', undefined, { timeout: 3000 })).toBeTruthy();
    expect(screen.getByText(HELP)).toBeTruthy();
  });

  it('says nothing when the importer only had its generic filler to write', async () => {
    // "API key for authentication" is what the importer writes when the seed
    // declares no notes. Rendering it would put a line under every one of the
    // hundreds of api_key integrations that restates the label above it.
    __TEMPLATE = {
      id: 't-plain', credential_name: 'plain', display_name: 'Plain', icon_slug: 'plain',
      auth_type: 'api_key', source: 'catalog',
      properties: [
        { name: 'api_key', displayName: 'Api Key', type: 'password', required: true,
          description: 'API key for authentication' },
      ],
    };
    renderWizard('plain', 'Plain');

    expect(await screen.findByText('API Key', undefined, { timeout: 3000 })).toBeTruthy();
    expect(screen.queryByText('API key for authentication')).toBeNull();
  });

  it('reads the secret field, not a url-variable registered beside it', async () => {
    // An api_key integration can carry extra props for its base-URL vars
    // ({subdomain}, {shop}). Picking "the first property with a description"
    // would show that one's text under the key input instead.
    __TEMPLATE = {
      id: 't-sub', credential_name: 'sub', display_name: 'Sub', icon_slug: 'sub',
      auth_type: 'api_key', source: 'catalog',
      properties: [
        { name: 'subdomain', displayName: 'Subdomain', type: 'string', required: true,
          description: 'Your account subdomain.' },
        { name: 'api_key', displayName: 'Api Key', type: 'password', required: true, description: HELP },
      ],
    };
    renderWizard('sub', 'Sub');

    expect(await screen.findByText('API Key', undefined, { timeout: 3000 })).toBeTruthy();
    expect(screen.getByText(HELP)).toBeTruthy();
  });
});
