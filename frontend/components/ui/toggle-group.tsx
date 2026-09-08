"use client";

import React from "react";
import { cn } from "../../lib/utils";
import { Badge } from "./badge";

interface ToggleOption {
  value: string;
  label: React.ReactNode;
  badge?: string;
  icon?: React.ReactNode;
  className?: string;
}

export interface ToggleGroupProps {
  value: string;
  onValueChange: (value: string) => void;
  options: ToggleOption[];
  hasBorder?: boolean;
  disabled?: boolean;
  className?: string;
  variant?: "grid" | "pill";
  activeClassName?: string;
  inactiveClassName?: string;
  /**
   * Names the group for a screen reader, AND opts it into radio semantics.
   *
   * <p>The control is a radio group by nature: exactly one option is selected
   * and picking another replaces it. Announcing that needs two roles working
   * together (`radiogroup` around `radio`), and a `radio` without an owning
   * group is an ARIA violation an audit tool will flag. A radiogroup with no
   * name is not much better: the reader hears the options and nothing saying
   * what they decide.
   *
   * <p>So the roles are emitted only when a caller supplies the name, and every
   * caller that does not is rendered exactly as before. This is deliberately a
   * one-way door: adding a label to an existing toggle upgrades it, and no
   * existing toggle is changed by somebody else's decision.
   */
  ariaLabel?: string;
}

export const ToggleGroup: React.FC<ToggleGroupProps> = ({
  value,
  onValueChange,
  options,
  hasBorder = true,
  disabled = false,
  className,
  variant = "grid",
  activeClassName,
  inactiveClassName,
  ariaLabel,
}) => {
  const isPill = variant === "pill";

  const containerClasses = cn(
      hasBorder ? "border border-theme transition-colors duration-150" : "transition-colors duration-150",
    isPill
      ? "bg-theme-tertiary rounded-2xl p-1 flex items-center gap-1"
      : "bg-theme-tertiary rounded-xl p-1.5",
    className,
  );

  const wrapperClasses = isPill
    ? "flex items-center gap-1"
    : "grid grid-cols-2 gap-2";

  const baseButtonClasses = isPill
    ? "px-3 h-9 rounded-xl text-sm font-medium transition-colors duration-150 cursor-pointer flex items-center justify-center gap-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-theme-primary/60"
    : "py-2 px-6 rounded-lg font-medium text-sm transition-colors duration-150 cursor-pointer flex items-center justify-center gap-1 ring-offset-background focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-1";

  const defaultActiveClass = isPill
    ? "bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm"
    : "bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm";

  // Hover is written with ARBITRARY VALUES, not with the app's `*-theme-*` classes.
  //
  // Those classes are hand-written CSS in `@layer components` (globals.css), so Tailwind v4 does
  // not own them: it emits `.bg-theme-primary`, but never `hover:bg-theme-primary`, and never an
  // opacity modifier like `bg-theme-primary/10`. The pill's inactive state used to ask for exactly
  // those two, so BOTH were absent from the stylesheet and the control had no hover feedback at
  // all - the chat/studio switch above the composer looked inert under the pointer. Compiling
  // globals.css confirms it: `.text-theme-secondary` is emitted, `hover:text-theme-primary` is not.
  //
  // The grid variant below never had the bug, because it already wrote its hover as an arbitrary
  // value. That is the form to copy.
  //
  // The TOKEN is `--bg-secondary`, not `--bg-hover`. The app moves one step along its surface
  // ladder on hover - Button's `outline` (transparent) hovers to `--bg-secondary`, its
  // `secondary` hovers to `--bg-tertiary`, and panel-tab documents the same ladder explicitly -
  // and `--bg-hover` is the far end of it, the token a SELECTED item wears. Using it for a hover
  // painted the option as though it had been chosen. `--bg-secondary` is the step toward the
  // active surface in both themes (#eceff3 -> #f5f6f8 on white, #2a2925 -> #1f1e1b on dark), so it
  // reads as "you are about to pick this" and needs no per-theme override.
  //
  // The ACTIVE option deliberately gets no hover ground: it is the selected segment, clicking it
  // does nothing (`onValueChange` early-returns on the current value), and a ground that lights up
  // under the pointer would promise an action that will not happen.
  const defaultInactiveClass = isPill
    ? "text-theme-secondary hover:text-[var(--text-primary)] hover:bg-[var(--bg-secondary)]"
    : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]";

  const resolvedActiveClass = activeClassName ?? defaultActiveClass;
  const resolvedInactiveClass = inactiveClassName ?? defaultInactiveClass;

  return (
    <div
      className={containerClasses}
      role={ariaLabel ? 'radiogroup' : undefined}
      aria-label={ariaLabel}
    >
      <div className={wrapperClasses}>
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            // Paired with the group role above, and emitted on the same
            // condition: a radio outside a radiogroup is an ARIA violation, so
            // the two are never split. Where they do apply, a screen reader
            // says the options are alternatives and which one is in force -
            // which on a screen that decides who pays for a purchase is the
            // whole meaning of the control.
            role={ariaLabel ? 'radio' : undefined}
            aria-checked={ariaLabel ? value === option.value : undefined}
            disabled={disabled}
            onClick={() => !disabled && onValueChange(option.value)}
            className={cn(
              baseButtonClasses,
              value === option.value
                ? resolvedActiveClass
                : resolvedInactiveClass,
              disabled && "cursor-not-allowed opacity-50",
              option.className,
            )}
          >
            <div
              className={cn(
                "flex items-center justify-center gap-1",
                !option.icon && "gap-0",
              )}
            >
              {option.icon && (
                <span className="inline-flex items-center justify-center">
                  {option.icon}
                </span>
              )}
              {option.label && (
                <span className={cn(option.icon && "flex items-center gap-1")}>
                  {option.label}
                </span>
              )}
              {option.badge && (
                <Badge
                  variant="outline"
                  className="bg-green-100 dark:bg-green-800 border-green-200 dark:border-green-700 text-green-800 dark:text-green-100"
                >
                  {option.badge}
                </Badge>
              )}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
};
