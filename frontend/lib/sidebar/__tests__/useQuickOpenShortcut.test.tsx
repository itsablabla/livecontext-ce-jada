// @vitest-environment jsdom
/**
 * The globally-bound half of the quick-open shortcut.
 *
 * It lives in the app shell rather than on the home button because the
 * customize menu prints these keys wherever the sidebar is, so they have to
 * work there too, not only on the page the button lives on. What matters here: it navigates through the app's own guard, it
 * actually swallows the keystroke (otherwise the browser's own Ctrl+Shift+O
 * runs as well), it stands down for a dialog, and it follows the preference
 * while mounted.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const navigate = vi.fn();
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => navigate,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select';
import { DEFAULT_HIDDEN_NAV_IDS, findNavItem } from '@/lib/sidebar/navItems';
import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';
import { useQuickOpenShortcut } from '../useQuickOpenShortcut';

function Shell() {
  useQuickOpenShortcut();
  return null;
}

/** Returns false when a listener called preventDefault, exactly like the DOM does. */
const press = (over: Partial<KeyboardEventInit> = {}) =>
  document.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'O', ctrlKey: true, shiftKey: true, bubbles: true, cancelable: true, ...over }),
  );

beforeEach(() => {
  navigate.mockClear();
  window.localStorage.clear();
  useSidebarNavStore.setState({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS], quickOpenNavId: 'agenda' });
});
afterEach(cleanup);

describe('the quick-open shortcut, bound app-wide', () => {
  it('opens the page picked in the customize menu', () => {
    useSidebarNavStore.setState({ quickOpenNavId: 'workflows' });
    render(<Shell />);

    press();

    expect(navigate).toHaveBeenCalledWith(findNavItem('workflows')!.path);
  });

  it('accepts Cmd+Shift+O too, since Mac keyboards report meta rather than ctrl', () => {
    render(<Shell />);

    press({ ctrlKey: false, metaKey: true });

    expect(navigate).toHaveBeenCalledWith(findNavItem('agenda')!.path);
  });

  it('swallows the keystroke, so the browser does not act on it as well', () => {
    // Chrome and Edge open the bookmark manager on this chord. Without
    // preventDefault the app would navigate AND the browser would too.
    render(<Shell />);

    expect(press()).toBe(false);
  });

  it('leaves a keystroke it does not claim entirely alone', () => {
    render(<Shell />);

    // Not cancelled, so whatever else listens for it still gets its turn.
    expect(press({ key: 'k', shiftKey: false })).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('fires while a text field has focus, which is where the caret sits on the home page', () => {
    render(<Shell />);
    const composer = document.createElement('textarea');
    document.body.appendChild(composer);
    composer.focus();

    composer.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'O', ctrlKey: true, shiftKey: true, bubbles: true, cancelable: true }),
    );

    expect(navigate).toHaveBeenCalledWith(findNavItem('agenda')!.path);
    composer.remove();
  });

  it('stands down while the dialog primitive the app really uses owns the screen', () => {
    // Rendered through `components/ui/dialog`, the primitive 34 files use, NOT
    // a hand-made div: Radix deliberately omits `aria-modal`, so a guard
    // written against that attribute passes a hand-made fixture and is inert
    // against every real dialog in the app. This goes green because the guard
    // reads the page lock Radix puts on `document.body` instead.
    render(
      <>
        <Shell />
        <Dialog open>
          <DialogContent>a question the user has not answered yet</DialogContent>
        </Dialog>
      </>,
    );
    expect(document.querySelector('[aria-modal="true"]'), 'Radix still sets no aria-modal').toBeNull();

    // Not cancelled either: the dialog's own Escape/Enter handling is untouched.
    expect(press()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('stands down for a hand-rolled modal too, which declares itself the other way', () => {
    // e.g. RunActionButton's cancel confirmation: `role="alertdialog"` +
    // `aria-modal="true"`, rendered by hand rather than through Radix, so it
    // locks nothing and is only visible through the attribute.
    render(<Shell />);
    const modal = document.createElement('div');
    modal.setAttribute('role', 'alertdialog');
    modal.setAttribute('aria-modal', 'true');
    document.body.appendChild(modal);

    expect(press()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    modal.remove();
  });

  it('still fires with a popover open, which is where the shortcut is advertised', () => {
    // Radix gives Popover content the SAME role="dialog" + data-state="open" as
    // a Dialog. The customize menu that prints these keys IS a popover, so a
    // guard written on that role would leave the shortcut dead exactly where a
    // user has just read it.
    render(
      <>
        <Shell />
        <Popover open>
          <PopoverTrigger>menu</PopoverTrigger>
          <PopoverContent>the customize menu</PopoverContent>
        </Popover>
      </>,
    );
    expect(document.querySelector('[role="dialog"]'), 'a popover really does claim that role').not.toBeNull();

    press();

    expect(navigate).toHaveBeenCalledWith(findNavItem('agenda')!.path);
  });

  it('resumes once the dialog closes, without the shell remounting', () => {
    // Re-rendered rather than unmounted, so this pins the SAME listener coming
    // back to life; unmounting the shell and mounting a fresh one would only
    // prove the body attribute is cleaned up.
    const shellAndDialog = (open: boolean) => (
      <>
        <Shell />
        <Dialog open={open}>
          <DialogContent>a question</DialogContent>
        </Dialog>
      </>
    );
    const { rerender } = render(shellAndDialog(true));
    expect(press(), 'the dialog must block while it is open').toBe(true);

    rerender(shellAndDialog(false));
    press();

    expect(navigate).toHaveBeenCalledWith(findNavItem('agenda')!.path);
  });

  it('stands down for a Select as well, which locks the page the same way', () => {
    // `@radix-ui/react-select` wraps its content in RemoveScroll unconditionally, so an
    // open dropdown sets the same lock a dialog does. Deliberate: a
    // focus-trapped popup awaiting a choice is as bad a moment to teleport.
    render(
      <>
        <Shell />
        <Select open>
          <SelectTrigger>pick</SelectTrigger>
          <SelectContent>
            <SelectItem value="a">a</SelectItem>
          </SelectContent>
        </Select>
      </>,
    );

    expect(press()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('follows a destination changed while it is already bound', () => {
    render(<Shell />);

    act(() => useSidebarNavStore.setState({ quickOpenNavId: 'files' }));
    press();

    expect(navigate).toHaveBeenCalledWith(findNavItem('files')!.path);
  });

  it('stops listening once the shell unmounts', () => {
    const { unmount } = render(<Shell />);
    unmount();

    press();

    expect(navigate).not.toHaveBeenCalled();
  });
});
