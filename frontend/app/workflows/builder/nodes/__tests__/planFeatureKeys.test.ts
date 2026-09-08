import { describe, expect, it } from 'vitest';
import { NODE_CLASSES } from '../nodeClasses';
import {
  PALETTE_NODE_TYPE_OVERRIDES,
  catalogFeatureKeys,
  nodeFeatureKey,
} from '../planFeatureKeys';
import { TRIGGER_TYPES } from '../../components/inspector/nodeTypes';

/**
 * The node types the backend documents (`node_type_documentation.type`), which is
 * exactly what the admin screen offers as a gate key.
 *
 * A palette entry that maps to anything else fails SILENTLY and in the worst
 * direction: the row shows no lock, the user adds the node, and the backend gate
 * fails it at run time. That is the whole reason this file exists.
 */
const DOCUMENTED_TYPES = new Set([
  // Read from orchestrator.node_type_documentation (prod, 2026-08-29). Update this
  // list in the same pass as any migration that adds or renames a node type.
  'agent', 'aggregate', 'approval', 'browser_agent', 'chat', 'classify', 'code',
  'compare_datasets', 'compression', 'convert_to_file', 'create_column', 'crypto_jwt',
  'database', 'data_input', 'date_time', 'decision', 'delete_row', 'download_file',
  'email_inbox', 'error', 'exit', 'extract_from_file', 'filter', 'find_rows', 'fork',
  'form', 'generate', 'get_rows', 'guardrail', 'html_extract', 'http_request',
  'insert_row', 'interface', 'limit', 'loop', 'manual', 'mcp', 'media', 'merge',
  'option', 'public_link', 'remove_duplicates', 'respond_to_webhook', 'response', 'rss',
  'schedule', 'send_email', 'set', 'sftp', 'sort', 'split', 'ssh', 'stop_on_error',
  'sub_workflow', 'summarize', 'switch', 'table', 'task', 'transform', 'update_row',
  'wait', 'webhook', 'workflow', 'xml',
]);

/**
 * Palette entries with no documented node type of their own. They CANNOT be gated
 * (the admin screen never offers them) and that is acceptable - but each one is
 * listed here deliberately, so a genuine typo cannot hide among them.
 */
const UNGATEABLE_PALETTE_IDS = new Set(['note']);

describe('planFeatureKeys', () => {
  describe('nodeFeatureKey', () => {
    it('prefixes the backend node type', () => {
      expect(nodeFeatureKey('merge')).toBe('node:merge');
      expect(nodeFeatureKey('media')).toBe('node:media');
    });

    it('translates the palette ids whose own name is not the node type', () => {
      // The palette calls the agent's shape "reasoning"; the engine calls it "agent".
      expect(nodeFeatureKey('ai-agent')).toBe('node:agent');
      expect(nodeFeatureKey('user-approval')).toBe('node:approval');
      expect(nodeFeatureKey('while-group')).toBe('node:loop');
      expect(nodeFeatureKey('http-request')).toBe('node:http_request');
    });

    it('gives each table operation its own key, though they share one palette kind', () => {
      // All five are `kind: 'crud'`; keying on that would gate all of them together
      // and match no documented type at all.
      expect(nodeFeatureKey('create-row')).toBe('node:insert_row');
      expect(nodeFeatureKey('read-row')).toBe('node:get_rows');
      expect(nodeFeatureKey('update-row')).toBe('node:update_row');
      expect(nodeFeatureKey('delete-row')).toBe('node:delete_row');
      expect(nodeFeatureKey('create-column')).toBe('node:create_column');
      expect(nodeFeatureKey('find-row')).toBe('node:find_rows');
    });

    it('maps every trigger the palette offers to its bare kind', () => {
      expect(nodeFeatureKey('webhook-trigger')).toBe('node:webhook');
      expect(nodeFeatureKey('schedule-trigger')).toBe('node:schedule');
      // Executes as 'datasource', documented as 'table' - the key follows the docs,
      // because that is what the admin screen can select.
      expect(nodeFeatureKey('tables-trigger')).toBe('node:table');

      for (const trigger of TRIGGER_TYPES) {
        const key = nodeFeatureKey(trigger.id);
        expect(key, `no key for trigger ${trigger.id}`).not.toBeNull();
        expect(
          DOCUMENTED_TYPES.has(key!.slice('node:'.length)),
          `trigger ${trigger.id} maps to ${key}, which nothing can gate`,
        ).toBe(true);
      }
    });

    it('returns null for an id that is not a node', () => {
      expect(nodeFeatureKey(null)).toBeNull();
      expect(nodeFeatureKey(undefined)).toBeNull();
      expect(nodeFeatureKey('not-a-node-class')).toBeNull();
      // A category tile opens a list, it never lands on the canvas.
      expect(nodeFeatureKey('triggers')).toBeNull();
    });

    it('produces a key the backend could actually match, for EVERY palette node class', () => {
      const unmatched: string[] = [];
      for (const klass of NODE_CLASSES) {
        const key = nodeFeatureKey(klass.id);
        if (!key) continue; // navigation-only entries are covered by their own test

        const type = key.slice('node:'.length);
        if (!DOCUMENTED_TYPES.has(type) && !UNGATEABLE_PALETTE_IDS.has(klass.id)) {
          unmatched.push(`${klass.id} -> ${type}`);
        }
      }
      expect(unmatched, 'these palette ids map to a type nothing can gate').toEqual([]);
    });

    it('does not carry an override for an id the palette no longer has', () => {
      const paletteIds = new Set<string>([
        ...NODE_CLASSES.map((k) => k.id),
        ...TRIGGER_TYPES.map((t) => t.id),
      ]);
      const stale = Object.keys(PALETTE_NODE_TYPE_OVERRIDES).filter((id) => !paletteIds.has(id));
      expect(stale, 'overrides for palette ids that no longer exist').toEqual([]);
    });
  });

  describe('catalogFeatureKeys', () => {
    it('asks about the endpoint before its API, so one endpoint can be held back further', () => {
      expect(catalogFeatureKeys('youtube-data-api', 'youtube-upload-video')).toEqual([
        'tool:youtube-upload-video',
        'api:youtube-data-api',
      ]);
    });

    it('lower-cases the slugs so a differently-cased one still matches its gate', () => {
      expect(catalogFeatureKeys('YouTube-Data-Api', 'YouTube-Upload')).toEqual([
        'tool:youtube-upload',
        'api:youtube-data-api',
      ]);
    });

    it('omits a missing slug rather than emitting a key like "tool:undefined"', () => {
      expect(catalogFeatureKeys('slack')).toEqual(['api:slack']);
      expect(catalogFeatureKeys(null, 'slack-post')).toEqual(['tool:slack-post']);
      expect(catalogFeatureKeys(null)).toEqual([]);
    });
  });
});
