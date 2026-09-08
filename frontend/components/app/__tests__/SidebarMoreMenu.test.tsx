// @vitest-environment jsdom
/**
 * The sidebar's overflow menu, in two layers.
 *
 * The first is the list of pages the sidebar is not showing: it is the only
 * route to them, so it has to be exactly their set and it has to navigate.
 * The second is the customize panel behind it, which keeps the promises it
 * always did: every page is listed, hidden ones included, and the last few
 * standing cannot be unticked.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { Store } from 'lucide-react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';

const safeNavigate = vi.fn();
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => safeNavigate,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

import {
  DEFAULT_HIDDEN_NAV_IDS,
  findNavItem,
  MIN_VISIBLE_NAV_ITEMS,
  SIDEBAR_NAV_ITEMS,
  type SidebarNavId,
} from '@/lib/sidebar/navItems';
import {
  SIDEBAR_NAV_ROW_ICON_CLASS,
  SIDEBAR_NAV_ROW_LABEL_CLASS,
  sidebarNavRowClass,
} from '@/lib/sidebar/navRowStyles';
import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';
import { NavIconButton } from '../NavIconButton';
import { SidebarMoreMenu } from '../SidebarMoreMenu';

/** Open the menu on its first view: the pages the sidebar is not showing. */
function openMenu(collapsed = false) {
  render(<SidebarMoreMenu collapsed={collapsed} />);
  fireEvent.click(screen.getByTestId('sidebar-more-trigger'));
}

/** Open the menu and walk through to the customize panel behind it. */
function openCustomizePanel(collapsed = false) {
  openMenu(collapsed);
  const toPanel = screen.queryByTestId('sidebar-more-customize');
  // With nothing in overflow the menu opens straight onto the panel, so there
  // is no button to click; that case is asserted on its own below.
  if (toPanel) fireEvent.click(toPanel);
}

function pageCheckbox(id: SidebarNavId): HTMLElement {
  const checkbox = document.getElementById(`sidebar-nav-${id}`);
  if (!checkbox) throw new Error(`The menu does not list the page "${id}"`);
  return checkbox;
}

/** The pages hidden when the sidebar is down to its floor; the first four survive. */
const floorHidden = SIDEBAR_NAV_ITEMS.slice(MIN_VISIBLE_NAV_ITEMS).map((item) => item.id);
const survivorId = SIDEBAR_NAV_ITEMS[0].id;

function atTheFloor() {
  useSidebarNavStore.setState({ hiddenNavIds: [...floorHidden], quickOpenNavId: 'agenda' });
}

beforeEach(() => {
  safeNavigate.mockClear();
  window.localStorage.clear();
  useSidebarNavStore.setState({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS], quickOpenNavId: 'agenda' });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('the customize panel behind the menu', () => {
  it('lists every page, including the ones currently hidden', () => {
    openCustomizePanel();

    SIDEBAR_NAV_ITEMS.forEach((item) => {
      expect(document.getElementById(`sidebar-nav-${item.id}`)).not.toBeNull();
    });
  });

  it('shows a page as unticked exactly when it is hidden', () => {
    openCustomizePanel();

    expect(pageCheckbox('board')).toHaveAttribute('data-state', 'unchecked');
    expect(pageCheckbox('interfaces')).toHaveAttribute('data-state', 'unchecked');
    expect(pageCheckbox('files')).toHaveAttribute('data-state', 'checked');
  });

  it('ticking a hidden page puts it back in the sidebar', () => {
    openCustomizePanel();

    fireEvent.click(pageCheckbox('board'));

    expect(useSidebarNavStore.getState().hiddenNavIds).not.toContain('board');
  });

  it('unticking a page removes it from the sidebar, and toggles once rather than twice', () => {
    openCustomizePanel();

    // Clicking the box must not ALSO fire the row handler wrapping it: the two
    // toggles would cancel out and nothing would move.
    fireEvent.click(pageCheckbox('files'));

    expect(useSidebarNavStore.getState().hiddenNavIds).toContain('files');
  });

  it('toggles from the row label too - most people click the page name, not the 16px box', () => {
    openCustomizePanel();

    // By id: the label also appears on the quick-open row below.
    fireEvent.click(document.getElementById('sidebar-nav-files')!.closest('div')!);

    expect(useSidebarNavStore.getState().hiddenNavIds).toContain('files');
  });

  it('picks the quick-open destination from the row label too', () => {
    openCustomizePanel();

    // By id, not by index into getAllByText: the label appears on the checkbox
    // row too, and an index would silently retarget if a third ever appeared.
    fireEvent.click(document.getElementById('sidebar-quick-open-board')!.closest('div')!);

    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('board');
  });

  it('does not let a locked row toggle from its label either', () => {
    atTheFloor();
    openCustomizePanel();

    // By id, like the sibling tests: an index into getAllByText would rely on
    // the checkbox list preceding the quick-open list in the DOM.
    fireEvent.click(document.getElementById(`sidebar-nav-${survivorId}`)!.closest('div')!);

    // Deep equality, not `not.toContain`: with the survivor already visible, a
    // "still not hidden" assertion would hold before the click too, and could
    // not tell a working guard from a dead one.
    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual(floorHidden);
  });

  it('locks the remaining checkboxes once the sidebar is down to its floor', () => {
    atTheFloor();
    openCustomizePanel();

    const survivor = pageCheckbox(survivorId);
    expect(survivor).toBeDisabled();

    fireEvent.click(survivor);
    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual(floorHidden);
  });

  it('leaves the hidden pages tickable at the floor, so the choice is reversible', () => {
    atTheFloor();
    openCustomizePanel();

    const hiddenOne = floorHidden[0];
    expect(pageCheckbox(hiddenOne)).not.toBeDisabled();

    fireEvent.click(pageCheckbox(hiddenOne));
    expect(useSidebarNavStore.getState().hiddenNavIds).not.toContain(hiddenOne);
  });

  it('picks the quick-open destination, hidden pages included', () => {
    openCustomizePanel();

    fireEvent.click(document.getElementById('sidebar-quick-open-board')!);

    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('board');
  });

  it('restores the defaults', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['files'], quickOpenNavId: 'workflows' });
    openCustomizePanel();

    fireEvent.click(screen.getByText('customize.reset'));

    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual([...DEFAULT_HIDDEN_NAV_IDS]);
    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('agenda');
  });

  it('is reachable from the collapsed rail too, where the sidebar spends much of its life', () => {
    openCustomizePanel(true);

    // Same menu, same pages - only the trigger differs.
    SIDEBAR_NAV_ITEMS.forEach((item) => {
      expect(document.getElementById(`sidebar-nav-${item.id}`)).not.toBeNull();
    });
  });

  it('tells the user how many pages the sidebar keeps, rather than silently refusing', () => {
    openCustomizePanel();

    const hint = screen.getByText(/customize\.pagesHint/);
    expect(within(hint).getByText(new RegExp(`"count":${MIN_VISIBLE_NAV_ITEMS}`))).toBeTruthy();
  });

  it('spells the quick-open keyboard shortcut next to the destination picker', () => {
    // The button carrying this shortcut only exists on the home page, while the
    // keys work everywhere, so this menu is where everyone else learns them.
    openCustomizePanel();

    const hint = screen.getByTestId('sidebar-customize-shortcut');
    // Exact spelling, because `shortcutKeys` is a PREFIX of `shortcutKeysMac`
    // under the key-echo translator mock: the loose form would also pass on the
    // Mac spelling and pin neither.
    expect(hint.textContent).toBe('customize.shortcutAria: shortcutKeys');
  });

  it('switches the menu hint to the Mac spelling on a Mac', () => {
    // The menu is now the primary place these keys are advertised, since the
    // button carrying them only appears on the home page.
    vi.stubGlobal('navigator', { platform: 'MacIntel' });

    openCustomizePanel();

    expect(screen.getByTestId('sidebar-customize-shortcut').textContent).toBe(
      'customize.shortcutAria: shortcutKeysMac',
    );
  });
});

describe('the overflow list the menu opens on', () => {
  it('offers exactly the pages the sidebar is not showing', () => {
    // The point of the whole control: a page taken out of the sidebar is one
    // click away here, not gone.
    openMenu();

    DEFAULT_HIDDEN_NAV_IDS.forEach((id) => {
      expect(screen.getByTestId(`sidebar-more-page-${id}`)).toBeTruthy();
    });
    const visible = SIDEBAR_NAV_ITEMS.filter((item) => !DEFAULT_HIDDEN_NAV_IDS.includes(item.id));
    visible.forEach((item) => {
      // A page already in the sidebar would be listed twice.
      expect(screen.queryByTestId(`sidebar-more-page-${item.id}`)).toBeNull();
    });
  });

  it('lists them in the order the sidebar itself uses, not the order they were hidden in', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['files', 'board'], quickOpenNavId: 'agenda' });

    openMenu();

    const listed = screen
      .getAllByTestId(/^sidebar-more-page-/)
      .map((row) => row.getAttribute('data-testid'));
    expect(listed).toEqual(['sidebar-more-page-board', 'sidebar-more-page-files']);
  });

  it('navigates through the surface it was given, so a page opens the same way a row would', () => {
    const onNavigate = vi.fn();
    render(<SidebarMoreMenu onNavigate={onNavigate} />);
    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));

    fireEvent.click(screen.getByTestId('sidebar-more-page-board'));

    expect(onNavigate).toHaveBeenCalledWith(findNavItem('board'));
    // Routing belongs to the surface; the menu must not also navigate itself.
    expect(safeNavigate).not.toHaveBeenCalled();
  });

  it('falls back to the guarded navigation the app uses when no surface supplies one', () => {
    openMenu();

    fireEvent.click(screen.getByTestId('sidebar-more-page-board'));

    expect(safeNavigate).toHaveBeenCalledWith(findNavItem('board')!.path);
  });

  it('closes once a page is picked, rather than leaving the menu over the new page', () => {
    openMenu();

    fireEvent.click(screen.getByTestId('sidebar-more-page-board'));

    expect(screen.queryByTestId('sidebar-more-overflow')).toBeNull();
  });

  it('keeps the customize panel one click behind it', () => {
    openMenu();
    expect(screen.queryByTestId('sidebar-customize-panel')).toBeNull();

    fireEvent.click(screen.getByTestId('sidebar-more-customize'));

    expect(screen.getByTestId('sidebar-customize-panel')).toBeTruthy();
    expect(screen.queryByTestId('sidebar-more-overflow')).toBeNull();
  });

  it('comes back to the pages from the panel', () => {
    openCustomizePanel();

    fireEvent.click(screen.getByTestId('sidebar-more-back'));

    expect(screen.getByTestId('sidebar-more-overflow')).toBeTruthy();
  });

  it('reopens on the pages, not wherever it was left', () => {
    openCustomizePanel();
    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));

    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));

    expect(screen.getByTestId('sidebar-more-overflow')).toBeTruthy();
  });

  it('opens straight onto the panel when the sidebar shows everything', () => {
    // Otherwise the first view would be a menu holding a single button.
    useSidebarNavStore.setState({ hiddenNavIds: [], quickOpenNavId: 'agenda' });

    openMenu();

    expect(screen.getByTestId('sidebar-customize-panel')).toBeTruthy();
    // And no way "back" to a list with nothing in it.
    expect(screen.queryByTestId('sidebar-more-back')).toBeNull();
  });
});

describe('what a screen reader is told', () => {
  it('names the popover for the view it is showing, and renames it on the switch', () => {
    // The name has to sit on Radix's role="dialog"; on the plain container it
    // would be a name on role=generic, which AT may ignore entirely.
    openMenu();
    const dialog = () => document.querySelector('[role="dialog"]');
    expect(dialog()).toHaveAttribute('aria-label', 'more.title');

    fireEvent.click(screen.getByTestId('sidebar-more-customize'));

    expect(dialog()).toHaveAttribute('aria-label', 'customize.title');
  });

  it('gives each view a named group, so the focus move lands somewhere with a name', () => {
    openMenu();
    const list = screen.getByTestId('sidebar-more-overflow');
    expect(list).toHaveAttribute('role', 'group');
    expect(list).toHaveAttribute('aria-label', 'more.title');

    fireEvent.click(screen.getByTestId('sidebar-more-customize'));

    const panel = screen.getByTestId('sidebar-customize-panel');
    expect(panel).toHaveAttribute('role', 'group');
    expect(panel).toHaveAttribute('aria-label', 'customize.title');
  });
});

describe('keyboard focus across the two views', () => {
  it('leaves the first open to Radix, which focuses and announces its own dialog', () => {
    openMenu();

    // Stealing focus here would skip the dialog announcement entirely.
    expect(document.activeElement).not.toBe(screen.getByTestId('sidebar-more-overflow'));
  });

  it('follows the switch into the panel instead of falling out of the menu', () => {
    // Switching view unmounts the button that was just pressed. Radix hands
    // focus back to the trigger when the popover CLOSES, which is not what
    // happened here, so without a deliberate move focus lands on <body> and a
    // keyboard user is dropped out of the menu mid-task.
    openMenu();

    fireEvent.click(screen.getByTestId('sidebar-more-customize'));

    expect(document.activeElement).toBe(screen.getByTestId('sidebar-customize-panel'));
  });

  it('follows it back to the pages as well', () => {
    openCustomizePanel();

    fireEvent.click(screen.getByTestId('sidebar-more-back'));

    expect(document.activeElement).toBe(screen.getByTestId('sidebar-more-overflow'));
  });
});

describe('what an overflow row actually is', () => {
  it('is a button carrying the page name, not an unlabelled clickable box', () => {
    // Every other test here reaches these rows by testid, which a bare
    // `<div onClick>` with no text would satisfy just as well. This is the one
    // that would notice.
    openMenu();

    const row = screen.getByTestId('sidebar-more-page-board');
    expect(row.tagName).toBe('BUTTON');
    // `type` matters: inside a form, a default-type button submits it.
    expect(row).toHaveAttribute('type', 'button');
    expect(row).toHaveTextContent('nav.board');
  });

  it('is reachable by keyboard, which a plain div would not be', () => {
    openMenu();
    const row = screen.getByTestId('sidebar-more-page-board');

    row.focus();

    // jsdom does not synthesise a button's Enter-to-click, so activation
    // itself cannot be asserted here; what CAN be, and is the part a div would
    // fail, is that the row takes focus at all. Enter then activates it
    // natively in a browser precisely because it is a <button> (asserted above).
    expect(document.activeElement).toBe(row);
  });

  it('marks the trigger itself while it holds the live page, so the RESTING sidebar says where you are', () => {
    // Marking the row inside the popover is not enough on its own: that row
    // only exists once the menu is open, and Applications is default-hidden,
    // so without this a user on it sees no active affordance anywhere until
    // they go looking.
    const { unmount } = render(<SidebarMoreMenu currentView="applications" />);
    expect(screen.getByTestId('sidebar-more-trigger')).toHaveAttribute('aria-current', 'page');
    unmount();

    // ...and not while the live page is one the sidebar draws itself.
    render(<SidebarMoreMenu currentView="agenda" />);
    expect(screen.getByTestId('sidebar-more-trigger')).not.toHaveAttribute('aria-current');
  });

  it('marks the collapsed trigger too, where the rail has no row to fall back on', () => {
    // The rail is the surface with the least room to say anything, so this is
    // the variant where losing the mark costs most.
    render(<SidebarMoreMenu collapsed currentView="applications" />);

    const trigger = screen.getByTestId('sidebar-more-trigger');
    expect(trigger).toHaveAttribute('aria-current', 'page');
    expect(trigger.className.split(/\s+/)).toContain('bg-surface-hover');
    expect(trigger.querySelector('svg')!.getAttribute('class')).toContain(
      'group-[.bg-surface-hover]:text-theme-primary',
    );
  });

  it('stops standing in for the row once the menu is open and the row itself is there', () => {
    // Both saying "current page" inside one navigation makes a screen reader
    // announce the same page twice.
    render(<SidebarMoreMenu currentView="applications" />);
    const trigger = screen.getByTestId('sidebar-more-trigger');
    expect(trigger).toHaveAttribute('aria-current', 'page');

    fireEvent.click(trigger);

    expect(trigger).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('sidebar-more-page-applications')).toHaveAttribute('aria-current', 'page');
  });

  it('wears that state the way its neighbours do, not just as a tint', () => {
    // NavIconButton and the panel rows both warm the icon and thicken the label
    // through `group-[.bg-surface-hover]:`. A trigger with the active
    // background but a secondary icon would read as a different kind of thing
    // sitting in the same column.
    render(<SidebarMoreMenu currentView="applications" />);

    const trigger = screen.getByTestId('sidebar-more-trigger');
    expect(trigger.className.split(/\s+/)).toContain('bg-surface-hover');
    expect(trigger.querySelector('svg')!.getAttribute('class')).toContain(
      'group-[.bg-surface-hover]:text-theme-primary',
    );
    expect(within(trigger).getByText('more.title').className).toContain(
      'group-[.bg-surface-hover]:font-medium',
    );
  });

  it('marks the page you are actually on, which for Applications is the only place that can', () => {
    // Applications is out of the sidebar by default now, so no row and no rail
    // icon can carry its active state - this list is all that is left.
    render(<SidebarMoreMenu currentView="applications" />);
    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));

    const active = screen.getByTestId('sidebar-more-page-applications');
    expect(active).toHaveAttribute('aria-current', 'page');
    expect(active.className.split(/\s+/)).toContain('bg-surface-hover');
    // Reads like an active sidebar row, which is weight and colour, not only a tint.
    expect(within(active).getByText('nav.applications').className).toContain('font-medium');
    // And only that one.
    expect(screen.getByTestId('sidebar-more-page-board')).not.toHaveAttribute('aria-current');
  });
});

describe('the way back appears and disappears with the list itself', () => {
  it('drops the way back when the last overflow page is put back from the panel', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['board'], quickOpenNavId: 'agenda' });
    openCustomizePanel();
    expect(screen.getByTestId('sidebar-more-back')).toBeTruthy();

    // Tick Board back into the sidebar: the list it would return to is now
    // empty. By id, because the quick-open radio below carries the same label.
    fireEvent.click(pageCheckbox('board'));

    expect(screen.queryByTestId('sidebar-more-back')).toBeNull();
  });

  it('leaves the list rather than sitting on an emptied one', () => {
    // The list can empty under the reader while it is the view being shown.
    // An empty list plus a lone Customize button is exactly the state the
    // open-time branch exists to avoid, so it must not be reachable this way
    // either.
    useSidebarNavStore.setState({ hiddenNavIds: ['board'], quickOpenNavId: 'agenda' });
    openMenu();
    expect(screen.getByTestId('sidebar-more-overflow')).toBeTruthy();

    act(() => useSidebarNavStore.setState({ hiddenNavIds: [] }));

    expect(screen.getByTestId('sidebar-customize-panel')).toBeTruthy();
    expect(screen.queryByTestId('sidebar-more-overflow')).toBeNull();
  });

  /**
   * The one state where the shown view and the stored view disagree: the menu
   * opened on a non-empty list, that list then emptied, and the derivation is
   * what is putting the panel on screen. Opening onto an already-empty list
   * does NOT produce it - `handleOpenChange` stores 'customize' outright - so a
   * test that starts there cannot see the bug these two guard.
   */
  function panelShownByDerivation() {
    useSidebarNavStore.setState({ hiddenNavIds: ['board'], quickOpenNavId: 'agenda' });
    openMenu();
    act(() => useSidebarNavStore.setState({ hiddenNavIds: [] }));
    expect(screen.getByTestId('sidebar-customize-panel')).toBeTruthy();
  }

  it('keeps the reader in the panel when their own tick refills the list', () => {
    // Without settling the view, refilling the list makes the derivation stop
    // applying and yanks the reader back out of the panel they are using.
    panelShownByDerivation();

    fireEvent.click(pageCheckbox('files'));

    expect(screen.getByTestId('sidebar-customize-panel')).toBeTruthy();
    expect(screen.queryByTestId('sidebar-more-overflow')).toBeNull();
  });

  it('keeps them there when Reset refills it too', () => {
    // Reset is the other control in this panel that repopulates the list.
    panelShownByDerivation();

    fireEvent.click(screen.getByText('customize.reset'));

    expect(screen.getByTestId('sidebar-customize-panel')).toBeTruthy();
    expect(screen.queryByTestId('sidebar-more-overflow')).toBeNull();
  });

  it('offers it once a page is taken out while the panel is open', () => {
    useSidebarNavStore.setState({ hiddenNavIds: [], quickOpenNavId: 'agenda' });
    openMenu();
    expect(screen.queryByTestId('sidebar-more-back')).toBeNull();

    fireEvent.click(pageCheckbox('files'));

    expect(screen.getByTestId('sidebar-more-back')).toBeTruthy();
  });
});

describe('the trigger, on both surfaces', () => {
  it('names itself with visible text when expanded, and not twice over', () => {
    render(<SidebarMoreMenu />);

    const trigger = screen.getByTestId('sidebar-more-trigger');
    expect(trigger).toHaveTextContent('more.title');
    // Neither an aria-label nor a title: both would repeat the visible text,
    // one to a screen reader and one as a tooltip of the row's own label.
    expect(trigger).not.toHaveAttribute('aria-label');
    expect(trigger).not.toHaveAttribute('title');
  });

  it('falls back to an aria-label when collapsed, where there is no text to read', () => {
    render(<SidebarMoreMenu collapsed />);

    const trigger = screen.getByTestId('sidebar-more-trigger');
    expect(trigger).toHaveAttribute('aria-label', 'more.title');
    // And the tooltip, which is the only thing naming an icon-only control for
    // a sighted mouse user. The expanded variant deliberately has neither.
    expect(trigger).toHaveAttribute('title', 'more.title');
    // `ghostGray` would pin the icon to the button's own colour - the mismatch
    // the rail was already fixed for once.
    expect(trigger.className).not.toContain('[&_svg]:!text-current');
  });

  it('renders the collapsed variant bare, so the rail can place it like any icon', () => {
    // The expanded row wrapper would resolve to two empty divs here, and would
    // put the icon somewhere the rail did not ask for.
    const { container } = render(<SidebarMoreMenu collapsed />);

    expect(container.firstElementChild).toBe(screen.getByTestId('sidebar-more-trigger'));
  });

  it('wears the overflow dots on both, and on the trigger itself', () => {
    // Three dots, the standard "there is more behind this" affordance. Asserted
    // ON the trigger, not anywhere in the tree: the menu draws one icon per row
    // too, so a container-wide query would pass on any of them.
    const { unmount } = render(<SidebarMoreMenu />);
    expect(screen.getByTestId('sidebar-more-trigger').querySelector('.lucide-ellipsis')).not.toBeNull();
    unmount();

    render(<SidebarMoreMenu collapsed />);
    expect(screen.getByTestId('sidebar-more-trigger').querySelector('.lucide-ellipsis')).not.toBeNull();
  });

  it('opens the same list from the collapsed rail, where the sidebar spends much of its life', () => {
    openMenu(true);

    DEFAULT_HIDDEN_NAV_IDS.forEach((id) => {
      expect(screen.getByTestId(`sidebar-more-page-${id}`)).toBeTruthy();
    });
  });
});

describe('the expanded row lines up with the rows above it', () => {
  /**
   * The bug this pins: the row was a `<Button>`, and the button system's base
   * style carries `gap-2`. The icon carried the navigation rows' own `mr-2` on
   * top of it, so the word "More" sat 8px to the right of "Files", "Tables" and
   * every other label in the block - the one row in the sidebar that did not
   * line up with the others.
   *
   * Pinned against the SHARED strings rather than a literal class list, so the
   * test cannot pass by re-copying whatever the row happens to say today: it
   * fails the moment this row stops being drawn from the same source as the
   * rows it sits under.
   */
  it('is drawn from the same class list a page row is', () => {
    render(<SidebarMoreMenu />);

    const trigger = screen.getByTestId('sidebar-more-trigger');
    expect(trigger.className).toBe(sidebarNavRowClass(false));
    // `toContain`, because lucide prepends its own `lucide lucide-<name>`
    // identity to whatever class the caller passes.
    expect(trigger.querySelector('svg')!.getAttribute('class')).toContain(SIDEBAR_NAV_ROW_ICON_CLASS);
    expect(trigger.querySelector('span')!.className).toBe(SIDEBAR_NAV_ROW_LABEL_CLASS);
  });

  it('paints the active surface exactly as a page row does when it holds the live page', () => {
    render(<SidebarMoreMenu currentView={findNavItem(DEFAULT_HIDDEN_NAV_IDS[0])!.view} />);

    expect(screen.getByTestId('sidebar-more-trigger').className).toBe(sidebarNavRowClass(true));
  });

  it('gives the COLLAPSED trigger the same icon treatment as the rail icons beside it', () => {
    // The rail draws its page icons through NavIconButton; this trigger draws
    // its own. They sit in the same 32px column, so any class one has and the
    // other lacks is a visible difference in a row of eight identical boxes -
    // and it is exactly how this trigger ended up the only 16px icon in the
    // rail without `shrink-0`. Compared against the rail's own component so the
    // pair cannot drift apart again.
    const { unmount } = render(<NavIconButton icon={Store} title="Marketplace" onClick={() => undefined} />);
    const railIcon = (screen.getByTitle('Marketplace').querySelector('svg')!.getAttribute('class') ?? '')
      .split(/\s+/)
      .filter((c) => c && !c.startsWith('lucide'));
    unmount();

    render(<SidebarMoreMenu collapsed />);
    const triggerIcon = (screen.getByTestId('sidebar-more-trigger').querySelector('svg')!.getAttribute('class') ?? '')
      .split(/\s+/);

    for (const cls of railIcon) {
      expect(triggerIcon, cls).toContain(cls);
    }
  });

  it('adds no button padding or gap of its own, which is what pushed the label right', () => {
    render(<SidebarMoreMenu />);

    const classes = screen.getByTestId('sidebar-more-trigger').className.split(/\s+/);
    expect(classes).not.toContain('gap-2');
    expect(classes.filter((c) => /^px-/.test(c))).toEqual(['px-1']);
  });
});

describe("the menu's own three actions", () => {
  /**
   * Customize, Back and Reset were `ghostGray` overridden to
   * `text-theme-secondary`: grey label, no border, nothing to aim at. The one
   * control that opens the customize panel read as a caption rather than as the
   * button it is. They are ordinary `outline` buttons now, the shape used
   * across the app, and they keep that variant's `--text-primary` label.
   */
  function expectOrdinaryButton(testId: string) {
    const button = screen.getByTestId(testId);
    expect(button, testId).toHaveAttribute('data-variant', 'outline');
    // The override that made the label grey. Its return is the regression.
    expect(button.className.split(/\s+/), testId).not.toContain('text-theme-secondary');
  }

  beforeEach(() => {
    // Back only exists when there is a list to go back TO, so the sidebar has
    // to be hiding something for all three to be reachable.
    useSidebarNavStore.setState({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS] });
  });

  it('draws Customize as a button on the overflow list, where it is the only control', () => {
    openMenu();

    expectOrdinaryButton('sidebar-more-customize');
  });

  it.each(['sidebar-more-back', 'sidebar-more-reset'])('draws %s in the panel the same way', (testId) => {
    openCustomizePanel();

    expectOrdinaryButton(testId);
  });
});
