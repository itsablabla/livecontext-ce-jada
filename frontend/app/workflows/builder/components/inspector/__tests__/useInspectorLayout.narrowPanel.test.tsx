// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  useInspectorLayout,
  requiredColumnsWidth,
  shouldUseTabbedLayout,
  INSPECTOR_COLLAPSED_COLUMN_WIDTH,
  INSPECTOR_MIN_PARAMS_WIDTH,
  INSPECTOR_RESIZE_HANDLE_WIDTH,
  INSPECTOR_WIDE_PANEL_MARGIN,
} from '../useInspectorLayout';

/**
 * Minimal ResizeObserver double: records the observed element and lets a test
 * push a width, which is the only thing the hook reads.
 */
class FakeResizeObserver {
  static instances: FakeResizeObserver[] = [];
  callback: ResizeObserverCallback;
  observed: Element[] = [];
  disconnected = false;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    FakeResizeObserver.instances.push(this);
  }

  observe(target: Element) {
    this.observed.push(target);
  }

  unobserve() {}

  disconnect() {
    this.disconnected = true;
  }

  emit(width: number) {
    this.callback(
      [{ contentRect: { width } } as unknown as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    );
  }
}

function makePanel(initialWidth: number): HTMLElement {
  const element = document.createElement('div');
  element.getBoundingClientRect = () => ({ width: initialWidth }) as DOMRect;
  return element;
}

/** The default advanced layout: two 280px columns, both expanded. */
const DEFAULT_REQUIRED = requiredColumnsWidth({
  inputCollapsed: false,
  inputWidth: 280,
  outputCollapsed: false,
  outputWidth: 280,
});

describe('requiredColumnsWidth', () => {
  it('adds both side columns, their resize handles and the parameters minimum', () => {
    expect(
      requiredColumnsWidth({
        inputCollapsed: false,
        inputWidth: 280,
        outputCollapsed: false,
        outputWidth: 280,
      }),
    ).toBe(280 + INSPECTOR_RESIZE_HANDLE_WIDTH + INSPECTOR_MIN_PARAMS_WIDTH + 280 + INSPECTOR_RESIZE_HANDLE_WIDTH);
  });

  it('counts a collapsed column as its rail, not its stored width', () => {
    expect(
      requiredColumnsWidth({
        inputCollapsed: true,
        inputWidth: 500,
        outputCollapsed: true,
        outputWidth: 500,
      }),
    ).toBe(INSPECTOR_COLLAPSED_COLUMN_WIDTH * 2 + INSPECTOR_MIN_PARAMS_WIDTH);
  });

  it('grows with a dragged column - a fixed threshold would miss the overflow', () => {
    const narrow = requiredColumnsWidth({
      inputCollapsed: false,
      inputWidth: 280,
      outputCollapsed: false,
      outputWidth: 280,
    });
    const dragged = requiredColumnsWidth({
      inputCollapsed: false,
      inputWidth: 500,
      outputCollapsed: false,
      outputWidth: 500,
    });
    expect(dragged).toBeGreaterThan(narrow);
  });
});

describe('shouldUseTabbedLayout', () => {
  it('follows the window on its own', () => {
    expect(
      shouldUseTabbedLayout({ isWindowMobile: true, isNarrowPanel: false, isAdvanced: false, isFullscreen: false }),
    ).toBe(true);
  });

  it('ignores a narrow panel when the layout has no side columns to lose', () => {
    expect(
      shouldUseTabbedLayout({ isWindowMobile: false, isNarrowPanel: true, isAdvanced: false, isFullscreen: false }),
    ).toBe(false);
  });

  it('switches an advanced layout out of its columns when the panel is too narrow', () => {
    expect(
      shouldUseTabbedLayout({ isWindowMobile: false, isNarrowPanel: true, isAdvanced: true, isFullscreen: false }),
    ).toBe(true);
  });

  it('treats fullscreen like advanced - it renders the same columns', () => {
    expect(
      shouldUseTabbedLayout({ isWindowMobile: false, isNarrowPanel: true, isAdvanced: false, isFullscreen: true }),
    ).toBe(true);
  });

  it('stays on columns when nothing is narrow', () => {
    expect(
      shouldUseTabbedLayout({ isWindowMobile: false, isNarrowPanel: false, isAdvanced: true, isFullscreen: true }),
    ).toBe(false);
  });
});

describe('useInspectorLayout - narrow panel detection', () => {
  beforeEach(() => {
    FakeResizeObserver.instances = [];
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    // A wide WINDOW throughout: these tests are about the panel, and the two
    // signals must not be confused for one another.
    window.innerWidth = 1600;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reports a wide panel as not narrow', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(DEFAULT_REQUIRED + 200)));
    expect(result.current.isNarrowPanel).toBe(false);
    expect(result.current.isMobile).toBe(false);
  });

  it('reports a panel narrower than the columns need as narrow', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(DEFAULT_REQUIRED - 1)));
    expect(result.current.isNarrowPanel).toBe(true);
  });

  it('keeps isMobile tied to the WINDOW - the panel signal is reported separately', () => {
    // The caller combines them through shouldUseTabbedLayout, because only it
    // knows whether the layout about to render has side columns to lose.
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(400)));
    expect(result.current.isNarrowPanel).toBe(true);
    expect(result.current.isMobile).toBe(false);
  });

  it('follows live resizes of the panel', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(DEFAULT_REQUIRED + 200)));
    expect(result.current.isNarrowPanel).toBe(false);

    act(() => FakeResizeObserver.instances.at(-1)!.emit(500));
    expect(result.current.isNarrowPanel).toBe(true);
  });

  it('needs the extra margin to go back to columns, so a panel parked at the boundary cannot flip-flop', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(500)));
    expect(result.current.isNarrowPanel).toBe(true);

    // Wide enough for the columns, but inside the hysteresis band: still narrow.
    act(() => FakeResizeObserver.instances.at(-1)!.emit(DEFAULT_REQUIRED + 1));
    expect(result.current.isNarrowPanel).toBe(true);

    act(() => FakeResizeObserver.instances.at(-1)!.emit(DEFAULT_REQUIRED + INSPECTOR_WIDE_PANEL_MARGIN));
    expect(result.current.isNarrowPanel).toBe(false);
  });

  it('re-decides when a column is dragged wider, without any panel resize', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    // Fits the default columns, but not two 500px ones.
    act(() => result.current.measurePanel(makePanel(DEFAULT_REQUIRED + 20)));
    expect(result.current.isNarrowPanel).toBe(false);

    act(() => {
      result.current.columns.setInputWidth(500);
      result.current.columns.setOutputWidth(500);
    });
    expect(result.current.isNarrowPanel).toBe(true);
  });

  it('re-decides when the columns are collapsed, so a rail-only layout keeps its columns', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(400)));
    expect(result.current.isNarrowPanel).toBe(true);

    act(() => {
      result.current.columns.setInputCollapsed(true);
      result.current.columns.setOutputCollapsed(true);
    });
    // 400 > 32 + 200 + 32 + margin: the rails fit comfortably.
    expect(result.current.isNarrowPanel).toBe(false);
  });

  it('ignores a zero width - a hidden panel has no opinion about the layout', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(DEFAULT_REQUIRED + 200)));
    act(() => FakeResizeObserver.instances.at(-1)!.emit(0));
    expect(result.current.isNarrowPanel).toBe(false);
  });

  it('stops observing the previous element when the panel remounts', () => {
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(1200)));
    const first = FakeResizeObserver.instances.at(-1)!;

    act(() => result.current.measurePanel(null));
    expect(first.disconnected).toBe(true);
  });

  it('disconnects on unmount rather than leaving an observer on a detached panel', () => {
    const { result, unmount } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    act(() => result.current.measurePanel(makePanel(1200)));
    const observer = FakeResizeObserver.instances.at(-1)!;

    unmount();
    expect(observer.disconnected).toBe(true);
  });

  it('degrades quietly where ResizeObserver does not exist', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    const { result } = renderHook(() => useInspectorLayout({ isAdvanced: true }));
    expect(() => act(() => result.current.measurePanel(makePanel(400)))).not.toThrow();
    expect(result.current.isNarrowPanel).toBe(false);
  });
});
