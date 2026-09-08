/**
 * The registry half of the run-mode Params contract.
 *
 * A backend node reports a key; this registry gives it a human label. The two
 * halves are one contract, and the backend half is the only one the e2e can see:
 * a node the alignment spec cannot reach (it ends its run failing, it needs a
 * live LLM, it parks on a signal) has NOTHING checking that its reported keys
 * still have labels here. Renaming a key on one side and not the other is then
 * silent, and the Params column falls back to `humanizeKey` - which is exactly
 * the raw-key rendering this work removed.
 *
 * These are the pairs the e2e cannot cover. Each entry is the key a node
 * actually reports, verified against the node source.
 */
import { describe, expect, it } from 'vitest';

import { inputLabelRegistry } from '../input-label-registry';

/** `<inspector type>` -> keys its node reports that the e2e can never see. */
const UNREACHABLE_BY_E2E: Record<string, string[]> = {
  // StopOnErrorNode ends the run, so its row is always FAILED and the spec reads
  // only completed rows. Renamed from error_message / error_code to match
  // StopOnErrorConfig.
  stop_on_error: ['errorMessage', 'errorCode'],
  // PublicLinkNode: `file` is the plan's own param name (stepProcessor writes
  // params.file / ttl_minutes / disposition).
  public_link: ['file', 'ttl_minutes', 'disposition'],
  // DownloadFileNode reports these on BOTH exit paths since the rename.
  download_file: ['url', 'filename', 'mimeType'],
  // RespondToWebhookNode had a label waiting on a key it never sent.
  respond_to_webhook: ['statusCode', 'contentType', 'body', 'headers'],
  // SftpNode summarises the payload rather than dumping a whole file.
  sftp: ['localContentSize'],

};

describe('input label registry covers the keys the e2e cannot reach', () => {
  for (const [nodeType, keys] of Object.entries(UNREACHABLE_BY_E2E)) {
    it(`labels every key ${nodeType} reports`, () => {
      const labels = inputLabelRegistry[nodeType as keyof typeof inputLabelRegistry] ?? {};
      const unlabelled = keys.filter((key) => !labels[key]);
      expect(
        unlabelled,
        `${nodeType} reports these keys with no label, so the Params column shows a humanised raw key: ${unlabelled.join(', ')}`,
      ).toEqual([]);
    });
  }

  it('no longer labels the names those nodes stopped reporting', () => {
    // The other direction: a label left behind after a rename is a promise the
    // product cannot keep, and it is how `public_link.file` came to be declared
    // for years while the node sent `file_expression`.
    const stale: Array<[string, string]> = [
      ['stop_on_error', 'error_message'],
      ['stop_on_error', 'error_code'],
      ['public_link', 'file_expression'],
      ['download_file', 'url_expression'],
      ['download_file', 'filename_expression'],
      ['download_file', 'mime_type_expression'],
    ];
    const leftovers = stale.filter(([nodeType, key]) => {
      const labels = inputLabelRegistry[nodeType as keyof typeof inputLabelRegistry] ?? {};
      return Boolean(labels[key]);
    });
    expect(leftovers, `stale labels: ${JSON.stringify(leftovers)}`).toEqual([]);
  });
});
