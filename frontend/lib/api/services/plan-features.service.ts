/**
 * Plan Features Service
 *
 * Which workflow nodes and catalog integrations each subscription plan includes.
 * The read is open to any signed-in user (the builder needs it to draw a lock
 * before a node is picked); the writes are platform-admin only and 403 otherwise.
 */

import { apiClient } from '../api-client';

/** Key shapes the backend resolves. See V457__plan_feature_requirements.sql. */
export type PlanFeatureKey =
  | `node:${string}`
  | `api:${string}`
  | `tool:${string}`
  | `feature:${string}`;

export interface PlanFeatureMap {
  /** featureKey -> minimum plan code. A key that is absent is on every plan. */
  requirements: Record<string, string>;
  /** The caller's effective plan, or '__NONE__' when they have no subscription. */
  planCode: string;
  /** The plan codes an admin may require, cheapest first. */
  selectablePlans: string[];
}

export interface PlanFeatureRequirementRow {
  featureKey: string;
  minPlan: string;
  label: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface PlanFeatureAdminList {
  requirements: PlanFeatureRequirementRow[];
  selectablePlans: string[];
}

export class PlanFeaturesService {
  /** The gate map plus the caller's own plan, in one request. */
  async getForCaller(): Promise<PlanFeatureMap> {
    return apiClient.get<PlanFeatureMap>('/plan-features');
  }

  /** Every stored requirement, with its label and who last changed it (admin). */
  async listForAdmin(): Promise<PlanFeatureAdminList> {
    return apiClient.get<PlanFeatureAdminList>('/plan-features/admin');
  }

  /**
   * Sets the minimum plan for one feature. Passing `FREE` CLEARS the requirement:
   * a stored FREE and no row mean the same thing, so the table only ever holds
   * the exceptions.
   */
  async setRequirement(featureKey: string, minPlan: string, label?: string) {
    return apiClient.put<{ success: boolean; featureKey: string; minPlan: string | null }>(
      '/plan-features/admin',
      { featureKey, minPlan, label },
    );
  }
}

export const planFeaturesService = new PlanFeaturesService();
