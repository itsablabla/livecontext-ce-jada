import { describe, expect, it } from 'vitest';
import { normalizeApiPath } from '../api-client';

describe('normalizeApiPath', () => {
  it('strips the query string', () => {
    expect(normalizeApiPath('/workflows?page=0&size=20')).toBe('/workflows');
  });

  it('strips a hash fragment', () => {
    expect(normalizeApiPath('/workflows#top')).toBe('/workflows');
  });

  it('replaces a UUID segment with :id', () => {
    expect(normalizeApiPath('/workflows/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c/runs'))
      .toBe('/workflows/:id/runs');
  });

  it('replaces an uppercase UUID segment with :id', () => {
    expect(normalizeApiPath('/publications/3F2A9C1E-7B4D-4E8A-9F0C-1D2E3F4A5B6C'))
      .toBe('/publications/:id');
  });

  it('replaces an all-digit segment with :id', () => {
    expect(normalizeApiPath('/credentials/12345')).toBe('/credentials/:id');
  });

  it('replaces several ids in one path and still drops the query', () => {
    expect(normalizeApiPath('/workflows/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c/runs/42/steps?epoch=1'))
      .toBe('/workflows/:id/runs/:id/steps');
  });

  it('leaves a plain path untouched', () => {
    expect(normalizeApiPath('/data-sources')).toBe('/data-sources');
  });

  it('does not touch a segment that merely contains digits', () => {
    expect(normalizeApiPath('/v2/catalog-bundles/latest')).toBe('/v2/catalog-bundles/latest');
  });

  it('masks a name-bearing segment past the first two route words (a credential name is user content)', () => {
    expect(normalizeApiPath('/credentials/by-name/My%20Slack%20Key')).toBe('/credentials/by-name/:id');
    expect(normalizeApiPath('/storage/files/report.pdf/download')).toBe('/storage/files/:id/download');
    expect(normalizeApiPath('/v2/workflows/dag/execute')).toBe('/v2/workflows/dag/execute');
  });
});
