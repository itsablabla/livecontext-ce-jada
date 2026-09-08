'use client';

import React, { memo } from 'react';
import type { LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface NavIconButtonProps {
  icon: LucideIcon;
  title: string;
  onClick: (e: React.MouseEvent) => void;
  isActive?: boolean;
}

/**
 * One entry of the collapsed rail. It is the SAME navigation entry as the row
 * the expanded panel shows (ConversationSidebar: Home, Marketplace, Board,
 * Agents, ...), so it gets that row's icon treatment: `text-theme-secondary` at
 * rest, `text-theme-primary` on hover and while it is the active view, over a
 * discreet `bg-surface-hover`.
 *
 * It used to be a `ghostGray` Button, whose `[&_svg]:!text-current` forced the
 * icon to the button's own `--text-primary` (full black in light theme) and
 * whose hover INVERTED the tile (dark background, light icon). So the same
 * entry read as two different things depending on whether the panel was open,
 * which is the mismatch this fixes. Size is unchanged: a 32px box, a 16px icon.
 */
export const NavIconButton = memo(function NavIconButton({ icon: Icon, title, onClick, isActive = false }: NavIconButtonProps) {
  return (
    <Button
      onClick={onClick}
      // `ghost`, not `ghostGray`: the gray variant pins every nested svg to the
      // button's colour, which would win over the icon's own classes below.
      variant="ghost"
      size="icon"
      // Announced, not only tinted - same reason as the panel rows and the
      // overflow trigger: active state that only exists as a background is
      // invisible to a screen reader.
      aria-current={isActive ? 'page' : undefined}
      className={`group w-8 h-8 hover:bg-surface-hover hover:text-theme-primary ${isActive ? 'bg-surface-hover' : ''}`}
      title={title}
    >
      <Icon className="w-4 h-4 shrink-0 text-theme-secondary transition-colors group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary" />
    </Button>
  );
});
