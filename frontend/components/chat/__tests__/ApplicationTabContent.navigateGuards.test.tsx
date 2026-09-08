/**
 * @vitest-environment jsdom
 *
 * `safeOnAction` treats a navigate ref as exempt from every guard it applies to a
 * real action: the preview block, the running-run block, and the in-flight dedupe.
 * That exemption is only safe if "navigate" is identified correctly.
 *
 * Pre-fix the check was `ref.endsWith(':navigate')`, so a trigger LABELLED
 * "Navigate" (ref `trigger:navigate`) was mistaken for a page switch and inherited
 * all three exemptions - including, on a marketplace preview, reaching the action
 * handler against the PUBLISHER's tenant, which is exactly what the preview block
 * exists to prevent. These tests fail against that code.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import * as React from 'react';

const iframeOnActionRef = vi.hoisted(() => ({
  current: undefined as ((ref: string, data: Record<string, unknown>) => Promise<void> | void) | undefined,
}));

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseRender: vi.fn(), resetApplicationData: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({
  executionService: { scheduleExecuteNow: vi.fn(), triggerSpecific: vi.fn(), triggerManual: vi.fn() },
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ executionTotal: 0 }, { executeStep: vi.fn() }],
}));
// The marketplace preview: read-only against the publisher's tenant.
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: true }),
}));
vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: { htmlTemplate: '<form></form>', items: [] },
    isLoading: false, isFetching: false, isPlaceholderData: false, refetch: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({ setPendingActivateTab: () => undefined }));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), getTokenProvider: () => null, getAuthToken: async () => null },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: () => <div data-testid="toolbar-stub" />,
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: (props: { onAction?: (ref: string, data: Record<string, unknown>) => Promise<void> | void }) => {
    iframeOnActionRef.current = props.onAction;
    return <div data-testid="iframe-stub" />;
  },
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({ TriggerPanel: () => <div /> }));
vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({}),
}));
vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '', centeringCssFor: () => '',
}));
vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s), formatUtcTime: (s: string) => s,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import { ApplicationTabContent } from '../ApplicationTabContent';

const config = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };

function renderPreviewApp(onAction: (ref: string, data: Record<string, unknown>) => Promise<void>) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={config as any}
      runId="run_abc"
      workflowId="wf-1"
      onAction={onAction}
    />,
  );
}

describe('ApplicationTabContent - navigate exemption in safeOnAction', () => {
  beforeEach(() => {
    iframeOnActionRef.current = undefined;
    vi.stubGlobal('ResizeObserver', class {
      observe() { /* no-op */ }
      unobserve() { /* no-op */ }
      disconnect() { /* no-op */ }
    });
  });

  it('blocks a trigger merely LABELLED "Navigate" in preview instead of letting it through', async () => {
    const onAction = vi.fn(async () => undefined);
    renderPreviewApp(onAction);
    await act(async () => { await Promise.resolve(); });

    await act(async () => {
      await iframeOnActionRef.current?.('trigger:navigate', {});
    });

    // Pre-fix this ref matched endsWith(':navigate'), so it skipped the preview
    // block and reached the handler against the publisher's tenant.
    expect(onAction).not.toHaveBeenCalled();
  });

  it('still lets a real page switch through in preview - browsing pages is not a write', async () => {
    const onAction = vi.fn(async () => undefined);
    renderPreviewApp(onAction);
    await act(async () => { await Promise.resolve(); });

    await act(async () => {
      await iframeOnActionRef.current?.('interface:details:navigate', {});
    });

    expect(onAction).toHaveBeenCalledWith('interface:details:navigate', {});
  });

  it('lets the builder-authored page switch through too (trigger prefix, navigate event)', async () => {
    // InterfaceMappingsColumn emits this shape for every UI-authored navigation.
    const onAction = vi.fn(async () => undefined);
    renderPreviewApp(onAction);
    await act(async () => { await Promise.resolve(); });

    await act(async () => {
      await iframeOnActionRef.current?.('trigger:details:navigate', {});
    });

    expect(onAction).toHaveBeenCalledWith('trigger:details:navigate', {});
  });
});
