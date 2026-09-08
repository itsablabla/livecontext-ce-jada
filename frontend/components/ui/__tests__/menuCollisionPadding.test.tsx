// @vitest-environment jsdom
import * as React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

/**
 * The gutter a menu keeps from the edge of the screen.
 *
 * Radix defaults `collisionPadding` to 0 for both of these, which is how a
 * portalled menu ends up flush against the edge of a phone: technically inside
 * the viewport, visually clipped. The value is not observable in the DOM (Radix
 * feeds it to floating-ui), so the primitive is mocked and the props our wrapper
 * forwards are read directly. Without this, deleting the prop from both files
 * left every other test green.
 */
const popoverProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));
const tooltipProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));

vi.mock('@radix-ui/react-popover', () => ({
  Root: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Trigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Portal: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Content: React.forwardRef((props: Record<string, unknown>, _ref) => {
    popoverProps.current = props;
    return <div data-testid="popover" />;
  }),
}));

vi.mock('@radix-ui/react-tooltip', () => ({
  Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Root: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Trigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Portal: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Content: React.forwardRef((props: Record<string, unknown>, _ref) => {
    tooltipProps.current = props;
    return <div data-testid="tooltip" />;
  }),
}));

const selectProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));

vi.mock('@radix-ui/react-select', () => ({
  Root: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Trigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Portal: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Value: () => null,
  Icon: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  ScrollUpButton: () => null,
  ScrollDownButton: () => null,
  Viewport: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Group: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Label: () => null,
  Item: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  ItemText: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  ItemIndicator: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Separator: () => null,
  Content: React.forwardRef((props: Record<string, unknown>, _ref) => {
    selectProps.current = props;
    return <div data-testid="select" />;
  }),
}));

import { Popover, PopoverContent, PopoverTrigger } from '../popover';
import { Select, SelectContent, SelectItem, SelectTrigger } from '../select';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../tooltip';

describe('collision padding', () => {
  it('PopoverContent keeps a menu off the edge of the screen by default', () => {
    render(
      <Popover open>
        <PopoverTrigger>open</PopoverTrigger>
        <PopoverContent>body</PopoverContent>
      </Popover>,
    );
    expect(popoverProps.current?.collisionPadding).toBe(8);
  });

  it('PopoverContent still lets a call site choose its own gutter', () => {
    render(
      <Popover open>
        <PopoverTrigger>open</PopoverTrigger>
        <PopoverContent collisionPadding={24}>body</PopoverContent>
      </Popover>,
    );
    expect(popoverProps.current?.collisionPadding).toBe(24);
  });

  it('leaves Select alone, which already keeps a gutter of its own', () => {
    // Radix Select defaults collisionPadding to its own CONTENT_MARGIN (10).
    // Forwarding 8 here would quietly narrow it and disagree with the maths
    // Select does when it expands the list on scroll.
    render(
      <Select open value="a" onValueChange={() => {}}>
        <SelectTrigger>trigger</SelectTrigger>
        <SelectContent>
          <SelectItem value="a">A</SelectItem>
        </SelectContent>
      </Select>,
    );
    expect(selectProps.current).not.toBeNull();
    expect(selectProps.current?.collisionPadding).toBeUndefined();
  });

  it('TooltipContent keeps the same gutter', () => {
    render(
      <TooltipProvider>
        <Tooltip open>
          <TooltipTrigger>hover</TooltipTrigger>
          <TooltipContent>explanation</TooltipContent>
        </Tooltip>
      </TooltipProvider>,
    );
    expect(tooltipProps.current?.collisionPadding).toBe(8);
  });
});
