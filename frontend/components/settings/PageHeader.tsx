'use client';

import React, { type ComponentType, type SVGProps } from 'react';

interface PageHeaderProps {
  // Any SVG icon component that accepts a className (lucide icons + our own
  // inline icons like McpIcon). Only `className` is used below, so the prop
  // must not be artificially narrowed to LucideIcon.
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  // ReactNode so settings pages can decorate the title with status pills
  // (PR19: workspace-scope badge alongside the page title). Strings still
  // work - React renders them as text nodes.
  title: React.ReactNode;
  subtitle?: string;
  iconClassName?: string;
  /**
   * Heading level. Defaults to the h1 a settings PAGE owns. Pass 'h2' where the
   * header titles a section inside a page that already has its own heading - a tab
   * panel, for instance - so the level does not appear and disappear with the tab.
   * Purely semantic: both levels render with the same styling.
   */
  headingLevel?: 'h1' | 'h2';
}

/**
 * Consistent header for settings pages.
 * Renders immediately without auth dependency.
 */
export function PageHeader({
  icon: Icon,
  title,
  subtitle,
  iconClassName = "w-5 h-5 text-theme-primary",
  headingLevel: Heading = 'h1'
}: PageHeaderProps) {
  return (
    <div className="flex items-center gap-3">
      <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
        <Icon className={iconClassName} />
      </div>
      <div>
        <Heading className="text-lg font-semibold text-theme-primary">{title}</Heading>
        {subtitle && (
          <p className="text-sm text-theme-secondary">{subtitle}</p>
        )}
      </div>
    </div>
  );
}

export default PageHeader;
