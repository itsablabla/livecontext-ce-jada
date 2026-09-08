/**
 * PlanAvailabilityRule - flags a node the account's plan does not include.
 *
 * Validations:
 * #20  Node or endpoint above the subscribed plan (error)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType, isNoteNode } from '../core/nodeUtils';
import { featureKeysForBuilderNode } from '../../../nodes/planFeatureKeys';
import { normalizeRequirement, planMeets } from '@/lib/billing/planTier';

/**
 * The builder is deliberately PERMISSIVE about this: the node can be dragged,
 * dropped, configured and saved. It simply carries a visible error saying which
 * plan it needs, and the run stops on it.
 *
 * <p><b>Why not refuse it earlier.</b> Blocking the palette row looked tidier and
 * was not enforcement: dragging the integration and then picking a restricted
 * endpoint in the inspector reached the same place with no warning at all. The
 * only honest boundary is the backend one (catalog-service refuses the call,
 * NodePlanGate fails the node), so the builder's job is to SAY so early rather
 * than to pretend it can prevent it.
 *
 * <p><b>Silence while the answer is unknown.</b> Same contract as the
 * optional-component warnings: no plan gate in the context (loading, request
 * failed, CE build) means no issue at all, never a guessed one. Marking a node
 * the account actually owns would be worse than marking nothing.
 */
export class PlanAvailabilityRule extends BaseValidationRule {
  readonly ruleName = 'PlanAvailability' as const;
  /**
   * Not critical: this must not make the workflow un-saveable. The user may
   * legitimately build now and subscribe before running.
   */
  readonly isCritical = false;
  readonly priority = 11;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes, planGate } = context;

    if (!planGate || !planGate.requirements) {
      return this.buildResult(issues);
    }

    for (const node of nodes) {
      if (isNoteNode(node)) continue;

      const keys = featureKeysForBuilderNode(node.data);
      if (keys.length === 0) continue;

      // First key with a requirement decides, matching the backend's precedence:
      // an endpoint can be held back further than the integration it belongs to.
      let required: string | null = null;
      for (const key of keys) {
        required = normalizeRequirement(planGate.requirements[key]);
        if (required) break;
      }
      if (!required || planMeets(planGate.planCode, required)) continue;

      const nodeType = getNodeType(node);
      const label = node.data?.label;
      const norm = label ? normalizeLabel(label) : null;
      const elementKey = norm ? `${nodeType}:${norm}` : `${nodeType}:${node.id}`;

      issues.push(
        this.createError(
          elementKey,
          nodeType,
          `This node is available from the ${required} plan. It will fail when the workflow runs until the plan is upgraded.`,
          {
            rule: 'plan_upgrade_required',
            nodeId: node.id,
            requiredPlan: required,
          },
        ),
      );
    }

    return this.buildResult(issues);
  }
}
