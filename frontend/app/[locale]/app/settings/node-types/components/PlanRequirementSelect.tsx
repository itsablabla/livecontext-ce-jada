"use client";

import React from "react";
import { Lock } from "lucide-react";
import { useTranslations } from "next-intl";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { FREE_PLAN, SELECTABLE_PLANS } from "@/lib/billing/planTier";

interface PlanRequirementSelectProps {
  /** The stored requirement, or null when the feature is on every plan. */
  value: string | null;
  onChange: (minPlan: string) => void;
  disabled?: boolean;
  /** Plan codes offered, cheapest first. Defaults to the shared list. */
  options?: readonly string[];
}

/**
 * "Which plan does this need?" - one control, used by both the node list and the
 * integration list.
 *
 * <p>{@code FREE} is not an extra state: it is how the requirement is CLEARED, and
 * the backend deletes the row when it is chosen. Showing it as the first option
 * rather than as a separate "remove" button keeps the whole decision in one place,
 * and makes "available to everyone" the visible default it actually is.
 */
export function PlanRequirementSelect({
  value,
  onChange,
  disabled,
  options,
}: PlanRequirementSelectProps) {
  const t = useTranslations("nodeTypeSettings.plan");
  const plans = options && options.length > 0 ? options : SELECTABLE_PLANS;
  const current = value || FREE_PLAN;
  const isGated = current !== FREE_PLAN;

  return (
    <div className="flex items-center gap-2">
      {isGated && (
        <Lock className="h-3.5 w-3.5 flex-shrink-0 text-amber-600 dark:text-amber-400" aria-hidden />
      )}
      <Select value={current} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger
          aria-label={t("label")}
          className={`h-8 w-[136px] text-sm ${isGated ? "border-amber-500/40" : ""}`}
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {plans.map((plan) => (
            <SelectItem key={plan} value={plan} className="text-sm">
              {plan === FREE_PLAN ? t("everyone") : plan}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

export default PlanRequirementSelect;
