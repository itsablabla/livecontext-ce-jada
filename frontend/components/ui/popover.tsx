import * as React from "react"
import * as PopoverPrimitive from "@radix-ui/react-popover"
import { cn } from "@/lib/utils"

const Popover = PopoverPrimitive.Root

const PopoverTrigger = PopoverPrimitive.Trigger

const PopoverContent = React.forwardRef<
  React.ElementRef<typeof PopoverPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>
>(({ className, align = "center", sideOffset = 4, collisionPadding = 8, ...props }, ref) => (
  <PopoverPrimitive.Portal>
    <PopoverPrimitive.Content
      ref={ref}
      align={align}
      sideOffset={sideOffset}
      // A menu that opens hard against the edge of the screen reads as clipped
      // even when it is not; 8px of gutter is what the two call sites that had
      // already reached for this prop chose.
      collisionPadding={collisionPadding}
      className={cn(
        // Never wider than the space the viewport actually leaves for it.
        // Radix keeps a popover on screen by FLIPPING it to the opposite side and
        // shifting it along the ALIGNMENT axis only, so a `side="right"` menu
        // opened from the 256px sidebar drawer on a 410px phone has nowhere to
        // flip to and simply hung 118px off the right edge (the sidebar "More"
        // menu did exactly that). `--radix-popper-available-width` is measured
        // AFTER flip and shift, so it is generous whenever the menu does fit and
        // only bites when it genuinely does not. It leads the class list on
        // purpose: a call site that states its own `max-w-*` still wins through
        // twMerge, and `w-72` below is a different property, so both apply.
        "max-w-[var(--radix-popper-available-width,calc(100vw-1rem))]",
        // `bg-theme-primary` / `text-theme-primary`, NOT shadcn's stock
        // `bg-popover` / `text-popover-foreground`: this app defines no
        // `--popover` token, so those resolve to nothing and the popover renders
        // with no background at all, the page showing straight through it. Every
        // call site in the app already overrides the background for exactly that
        // reason; this default just means the next one does not have to know.
        // z-[64], not the stock z-50. The content is portalled to document.body,
        // so its z-index competes with the whole page rather than with its
        // trigger's container - and the app has a band of surfaces at 60-63 that
        // a popover is always opened FROM and must therefore paint over: the
        // desktop sidebar (z-[60]), the side panel when detached or full screen
        // (z-[61]) and that window's resize grips (z-[62]/z-[63]). At z-50 the
        // side panel's own tab menu and Add-tab picker simply did not appear
        // once the panel had a z-index, with no error and nothing to click.
        // AppSidebar had already patched around this locally with a z-[9999] on
        // its own menu; fixing the default stops the next surface in that band
        // from rediscovering it. Still far below every modal layer (z-[9999] and
        // up), so a dialog continues to paint over a popover as it always did.
        "z-[64] w-72 rounded-xl border bg-theme-primary p-4 text-theme-primary shadow-[0_8px_24px_rgba(0,0,0,0.12)] outline-none",
        className
      )}
      style={{ animation: 'none', transition: 'none' }}
      {...props}
    />
  </PopoverPrimitive.Portal>
))
PopoverContent.displayName = PopoverPrimitive.Content.displayName

export { Popover, PopoverTrigger, PopoverContent }

