import * as React from "react"
import * as TooltipPrimitive from "@radix-ui/react-tooltip"
import { cn } from "@/lib/utils"

const TooltipProvider = TooltipPrimitive.Provider

const Tooltip = TooltipPrimitive.Root

const TooltipTrigger = TooltipPrimitive.Trigger

const TooltipPortal = TooltipPrimitive.Portal

const TooltipContent = React.forwardRef<
  React.ElementRef<typeof TooltipPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TooltipPrimitive.Content>
>(({ className, sideOffset = 4, collisionPadding = 8, ...props }, ref) => (
  <TooltipPortal>
    <TooltipPrimitive.Content
      ref={ref}
      sideOffset={sideOffset}
      collisionPadding={collisionPadding}
      className={cn(
        // The same viewport cap PopoverContent carries, and for the same
        // reason. First in the list so the 26 call sites that set their own
        // `max-w-*` to make their text wrap keep deciding their own width.
        "max-w-[var(--radix-popper-available-width,calc(100vw-1rem))]",
        // Above every overlay in the app, deliberately. The content is portalled to
        // document.body, so its z-index competes with the whole page rather than with
        // its trigger's container: at the old z-[9999] a tooltip opened from inside the
        // composer menu (z-[10000]), its options panel (z-[99999]) or the plan-comparison
        // dialog (z-[100000]) painted UNDERNEATH the surface it belongs to and simply did
        // not appear. ModelInfo had already patched around this locally with its own
        // z-[100002]; fixing the default stops the next high-z surface from rediscovering
        // it. A tooltip is transient and anchored to a trigger the reader must be able to
        // hover, so there is no surface it should ever hide behind.
        "z-[100002] overflow-hidden rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100",
        className
      )}
      style={{ animation: 'none', transition: 'none' }}
      {...props}
    />
  </TooltipPortal>
))
TooltipContent.displayName = TooltipPrimitive.Content.displayName

export { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider }

