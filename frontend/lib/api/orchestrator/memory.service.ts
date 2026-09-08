/**
 * Memory Service
 *
 * Long-term memory: the durable facts agents accumulate about the people they
 * work with and the work itself, editable by hand in the Memory tab.
 *
 * Everything here is workspace-scoped by the gateway, not by these calls: the
 * active workspace travels as `X-Active-Organization-ID`, which `apiClient`
 * attaches on every request. So switching workspace switches the whole memory
 * set with no parameter passed from here, and no call must ever send an
 * organization id of its own.
 */

import { apiClient } from '../api-client';

/** The four buckets an entry can belong to. Matches the backend enum, lowercased on the wire. */
export type MemoryType = 'user' | 'feedback' | 'project' | 'reference';

/** Who wrote the entry: an agent during a run, or a person in the Memory tab. */
export type MemorySource = 'agent' | 'user';

/** `workspace` = every agent here sees it. `agent` = private to one agent. */
export type MemoryScope = 'workspace' | 'agent';

export interface Memory {
  id: string;
  /** Stable readable handle. This is what an agent passes to open the entry. */
  slug: string;
  title: string;
  /** The one line injected into every agent's context in this workspace. */
  summary: string;
  /**
   * The body. Never injected into any context, and never fetched by list or
   * search: it is the only unbounded field, nothing in a list renders it, and
   * shipping it per row made opening the tab cost the size of the workspace's
   * whole memory rather than its number of entries. A listed row is a
   * {@link MemoryRow}, which does not have this field at all; call
   * {@link MemoryService.get} before opening an editor.
   */
  content: string;
  type: MemoryType;
  tags: string[];
  /** When true the whole body is injected on every run, not just the summary. */
  pinned: boolean;
  source: MemorySource;
  agentId: string | null;
  scope: MemoryScope;
  isActive: boolean;
  /** How many times an agent opened this entry. The evidence for pruning later. */
  recallCount: number;
  lastRecalledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * The field caps, as the server ships them by default.
 *
 * The SERVER is authoritative: these come from `ai.agent.memory.*`, an operator
 * can tune them, and the backend refuses anything over its own figure. These
 * exist only so the editor can stop a person mid-keystroke instead of letting
 * them write six hundred characters and then lose them to a round trip. When the
 * two disagree the refusal is shown verbatim, which is why the mismatch is
 * annoying rather than dangerous - but keep them in step with
 * `MemoryLimitsConfig` all the same.
 */
export const MEMORY_FIELD_LIMITS = {
  title: 120,
  summary: 240,
  content: 8000,
} as const;

export interface MemoryWriteRequest {
  title: string;
  summary: string;
  content?: string;
  type?: MemoryType;
  tags?: string[];
  pinned?: boolean;
  /** Omit on create to derive it from the title; pass it to overwrite an existing entry. */
  slug?: string;
  /** Set to scope the entry to one agent instead of the whole workspace. */
  agentId?: string | null;
}

/**
 * A listed row: everything except the body.
 *
 * Distinct from {@link Memory} so that reading `row.content` and getting
 * undefined is a compile error rather than a blank editor.
 */
export type MemoryRow = Omit<Memory, 'content'>;

/** Partial edit: an omitted field is left unchanged rather than blanked. */
export type MemoryUpdateRequest = Partial<MemoryWriteRequest> & { isActive?: boolean };

export class MemoryService {
  /**
   * Every memory in the active workspace, both scopes, pinned first.
   *
   * Rows come back WITHOUT their body: see {@link Memory.content}. Use
   * {@link MemoryService.get} for the one entry you are about to show in full.
   */
  async list(): Promise<MemoryRow[]> {
    return apiClient.get<MemoryRow[]>('/memories');
  }


  /**
   * Word search over titles, summaries AND bodies, ranked by relevance.
   * Finds entries whose body mentions something the one-line summary does not.
   */
  async search(query: string, limit = 25): Promise<MemoryRow[]> {
    return apiClient.get<MemoryRow[]>('/memories/search', {
      params: { query, limit: String(limit) },
    });
  }

  async get(id: string): Promise<Memory> {
    return apiClient.get<Memory>(`/memories/${id}`);
  }

  async create(request: MemoryWriteRequest): Promise<Memory> {
    return apiClient.post<Memory>('/memories', request);
  }

  async update(id: string, request: MemoryUpdateRequest): Promise<Memory> {
    return apiClient.put<Memory>(`/memories/${id}`, request);
  }

  async remove(id: string): Promise<void> {
    await apiClient.delete(`/memories/${id}`);
  }
}

export const memoryService = new MemoryService();
