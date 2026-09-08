import { describe, expect, it } from 'vitest';
import {
  authTypeLabel,
  CATALOG_AUTH_TYPES,
  integrationIconSrc,
  integrationPath,
  integrationSummary,
  isIndexableIntegration,
  isValidIntegrationSlug,
  mapIntegration,
  mapIntegrationDetail,
  mapIntegrations,
  type PublicIntegration,
} from '../integrations';

function raw(overrides: Record<string, unknown> = {}) {
  return {
    slug: 'slack',
    name: 'Slack',
    description: 'Post messages, manage channels and read conversations.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 45,
    authType: 'bearer_token',
    ...overrides,
  };
}

function integration(overrides: Partial<PublicIntegration> = {}): PublicIntegration {
  return { ...(mapIntegration(raw()) as PublicIntegration), ...overrides };
}

describe('mapIntegration', () => {
  it('maps a well-formed row', () => {
    expect(mapIntegration(raw())).toEqual({
      slug: 'slack',
      name: 'Slack',
      description: 'Post messages, manage channels and read conversations.',
      iconSlug: 'slack',
      iconUrl: null,
      toolCount: 45,
      authType: 'bearer_token',
    });
  });

  it('drops a row with no slug or no name', () => {
    // Neither can make a card or a URL, and rendering it half-empty would put a
    // blank entry in the grid and a broken link in the structured data.
    expect(mapIntegration(raw({ slug: null }))).toBeNull();
    expect(mapIntegration(raw({ name: '   ' }))).toBeNull();
    expect(mapIntegration(null)).toBeNull();
    expect(mapIntegration('slack')).toBeNull();
  });

  it('falls back to the catalog glyph when a row carries no icon', () => {
    // "mcp" is the backend's own fallback and the file exists, so the card shows
    // a mark rather than a broken image.
    expect(mapIntegration(raw({ iconSlug: null }))?.iconSlug).toBe('mcp');
  });

  it('treats a missing description and a missing count as empty, not as undefined', () => {
    const mapped = mapIntegration(raw({ description: undefined, toolCount: undefined }));
    expect(mapped?.description).toBe('');
    expect(mapped?.toolCount).toBe(0);
  });
});

describe('mapIntegrations', () => {
  it('reads the page envelope and drops unusable rows', () => {
    const page = { content: [raw(), raw({ slug: null }), raw({ slug: 'github', name: 'GitHub' })] };

    expect(mapIntegrations(page).map((i) => i.slug)).toEqual(['slack', 'github']);
  });

  it('degrades to an empty list when the payload has no content array', () => {
    // A backend shape change must cost the section its rows, not take the page down.
    expect(mapIntegrations({})).toEqual([]);
    expect(mapIntegrations({ content: 'nope' })).toEqual([]);
    expect(mapIntegrations(null)).toEqual([]);
  });
});

describe('mapIntegrationDetail', () => {
  it('maps the integration, its documentation and its endpoints', () => {
    const detail = mapIntegrationDetail({
      integration: raw(),
      documentation: 'https://api.slack.com',
      tools: [
        { name: 'send_message', description: 'Post a message', method: 'POST' },
        { name: 'list_channels', description: '', method: 'GET' },
      ],
      toolsTruncated: true,
    });

    expect(detail?.integration.name).toBe('Slack');
    expect(detail?.documentation).toBe('https://api.slack.com');
    expect(detail?.tools).toEqual([
      { name: 'send_message', description: 'Post a message', method: 'POST' },
      { name: 'list_channels', description: '', method: 'GET' },
    ]);
    expect(detail?.toolsTruncated).toBe(true);
  });

  it('returns null when the payload carries no usable integration', () => {
    expect(mapIntegrationDetail({ tools: [] })).toBeNull();
    expect(mapIntegrationDetail(null)).toBeNull();
  });

  it('drops a nameless endpoint rather than rendering a blank row', () => {
    const detail = mapIntegrationDetail({
      integration: raw(),
      tools: [{ description: 'orphan' }, { name: 'ok' }],
    });

    expect(detail?.tools.map((t) => t.name)).toEqual(['ok']);
  });

  it('defaults truncation to false rather than to a missing value', () => {
    const detail = mapIntegrationDetail({ integration: raw(), tools: [] });
    expect(detail?.toolsTruncated).toBe(false);
  });
});

describe('isValidIntegrationSlug', () => {
  it('accepts the catalog slug shape', () => {
    expect(isValidIntegrationSlug('slack')).toBe(true);
    expect(isValidIntegrationSlug('google-drive')).toBe(true);
    expect(isValidIntegrationSlug('elevenlabs2')).toBe(true);
  });

  it('rejects anything else BEFORE a fetch is attempted', () => {
    // /integrations/{slug} is a dynamic route: every URL a scanner invents would
    // otherwise become one gateway request from the SSR pod, all sharing a single
    // anonymous rate-limit bucket.
    expect(isValidIntegrationSlug('')).toBe(false);
    expect(isValidIntegrationSlug('Slack')).toBe(false);
    expect(isValidIntegrationSlug('slack_api')).toBe(false);
    expect(isValidIntegrationSlug('slack--api')).toBe(false);
    expect(isValidIntegrationSlug('-slack')).toBe(false);
    expect(isValidIntegrationSlug('../../etc/passwd')).toBe(false);
    expect(isValidIntegrationSlug('a'.repeat(121))).toBe(false);
  });
});

describe('integrationPath and integrationIconSrc', () => {
  it('builds the canonical page path', () => {
    expect(integrationPath('slack')).toBe('/integrations/slack');
  });

  it('prefers an explicit icon URL over the slug-derived one', () => {
    expect(integrationIconSrc(integration())).toBe('/icons/services/slack.svg');
    expect(integrationIconSrc(integration({ iconUrl: '/custom/slack.png' })))
      .toBe('/custom/slack.png');
  });
});

describe('authTypeLabel', () => {
  it('labels EVERY value the catalog actually stores', () => {
    // The regression this exists for: the first version handled 'bearer' and
    // 'basic', which occur nowhere, so `bearer_token` (290 seeds) and
    // `basic_auth` (102) fell through to null and 40% of the catalog rendered
    // no badge, silently. Walking the real value set is what makes the next
    // invented value fail here instead of in production.
    for (const authType of CATALOG_AUTH_TYPES) {
      expect(authTypeLabel(authType), `no label for the real value "${authType}"`).not.toBeNull();
    }
  });

  it('names each one the way a visitor reads it', () => {
    expect(authTypeLabel('oauth2')).toBe('OAuth');
    expect(authTypeLabel('api_key')).toBe('API key');
    expect(authTypeLabel('bearer_token')).toBe('Token');
    expect(authTypeLabel('basic_auth')).toBe('Basic auth');
    expect(authTypeLabel('custom')).toBe('Custom auth');
  });

  it('calls out the integrations that need no credential at all', () => {
    // The one a visitor actually scans for: what they can try without opening an
    // account anywhere.
    expect(authTypeLabel('NONE')).toBe('No key needed');
  });

  it('drops the badge rather than showing a raw enum to a stranger', () => {
    // Right for a value the catalog gains LATER. Never a reason to leave a value
    // it already has unmapped: that is what the first test above pins.
    expect(authTypeLabel(null)).toBeNull();
    expect(authTypeLabel('something_new')).toBeNull();
  });
});

describe('integrationSummary', () => {
  it('returns a short description untouched', () => {
    expect(integrationSummary(integration())).toBe(
      'Post messages, manage channels and read conversations.',
    );
  });

  it('cuts a long description on a word boundary, never mid-word', () => {
    const long = `${'word '.repeat(60)}end`;

    const summary = integrationSummary(integration({ description: long }), 40);

    expect(summary.length).toBeLessThanOrEqual(40);
    expect(summary.endsWith('…')).toBe(true);
    expect(summary).not.toMatch(/wo…$/);
  });

  it('builds a sentence when the catalog row has no description', () => {
    // A blank meta description is flagged by search consoles, and a blank card
    // reads as a broken entry.
    expect(integrationSummary(integration({ description: '  ' })))
      .toBe('Connect Slack to your AI workflows and agents with LiveContext.');
  });
});

describe('isIndexableIntegration', () => {
  it('indexes an integration with enough endpoints to fill a page', () => {
    expect(isIndexableIntegration(integration({ toolCount: 3, description: '' }))).toBe(true);
    expect(isIndexableIntegration(integration({ toolCount: 45 }))).toBe(true);
  });

  it('indexes a small integration that describes itself properly', () => {
    expect(isIndexableIntegration(integration({ toolCount: 1, description: 'x'.repeat(80) })))
      .toBe(true);
  });

  it('keeps a thin page out of the index', () => {
    // One endpoint and a two-word description renders a name and a line. Enough
    // pages like that drag down the ranking of the whole domain, including the
    // ones that already perform.
    expect(isIndexableIntegration(integration({ toolCount: 1, description: 'An API.' })))
      .toBe(false);
    expect(isIndexableIntegration(integration({ toolCount: 2, description: '' }))).toBe(false);
  });
});
