'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Globe } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { WorkflowSaveWithVersions } from '@/components/workflow/WorkflowVersionHistory';
import { WorkflowRunSplitButton } from '@/components/workflow/WorkflowRunSplitButton';
import { PublishWorkflowModal } from '@/components/workflow/ShareWorkflowModal';
import { useWorkflowSaveState } from '@/hooks/useWorkflowSaveState';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { orchestratorApi } from '@/lib/api';

interface WorkflowPanelActionsProps {
  workflowId: string;
  /**
   * The canvas has a run bound. Taken from the run-panel bus, which is what the
   * CANVAS publishes, not from the surrounding WorkflowModeProvider: an embedded
   * canvas enters run mode IN PLACE, under a provider of its own, so the panel's
   * provider still reads "edit" while a run is on screen. Reading it there left
   * the Run button offering to start a second run of a workflow already running.
   */
  isRunMode: boolean;
  /** Frozen someone-else's workflow: none of the three apply. */
  isPreviewOnly: boolean;
  /**
   * The caller may change this workflow. False on a surface that resolved to
   * SOMEONE ELSE's workflow, which the application panel does for a publication
   * the caller has not acquired: publishing it is not theirs to do, and the
   * canvas is read-only there anyway. Defaults to true, which is what every
   * workflow surface that reaches its own tenant's workflow means.
   */
  canEdit?: boolean;
}

/**
 * Share / Save / Run for a workflow shown in the right side panel.
 *
 * These three live in the page header (ChatHeader), gated on being ON a workflow
 * page. A workflow opened as a side-panel tab - a sub-workflow, an application's
 * workflow, anything reached from a chat - is not on that page, so the header
 * showed nothing for it and the canvas offered no replacement: the workflow was
 * visible and unsaveable. This puts the same three controls in the panel's
 * sub-tab bar, beside the tabs rather than inside ReactFlow, where the canvas
 * chrome is already at capacity.
 *
 * Same components and the same events as the header, so behaviour is identical.
 * The events are now scoped by workflowId (see `isEventForWorkflow`), which is
 * what makes a second Save button safe at all: before that, one Save saved every
 * mounted canvas.
 */
export function WorkflowPanelActions({ workflowId, isRunMode, isPreviewOnly, canEdit = true }: WorkflowPanelActionsProps) {
  const t = useTranslations();
  const canMutate = useCanMutateInCurrentOrg();
  const { saveStatus, isDirty, isAgentStreaming, requestSave } = useWorkflowSaveState(workflowId);

  const [isShareOpen, setIsShareOpen] = useState(false);
  // Title + description prefill the publish wizard for a workflow that is not
  // published yet. Fetched on the first Share click rather than on mount: the
  // panel keeps its tabs mounted, so an eager fetch would cost one request per
  // opened tab for a dialog most of them never open.
  const [metadata, setMetadata] = useState<{ name: string; description: string } | null>(null);

  useEffect(() => {
    if (!isShareOpen || metadata || !workflowId) return;
    let cancelled = false;
    orchestratorApi.getWorkflow(workflowId)
      .then((workflow) => {
        if (cancelled) return;
        setMetadata({ name: workflow?.name ?? '', description: workflow?.description ?? '' });
      })
      // The wizard opens either way; it just starts with empty fields.
      .catch(() => { if (!cancelled) setMetadata({ name: '', description: '' }); });
    return () => { cancelled = true; };
  }, [isShareOpen, metadata, workflowId]);

  const openShare = useCallback(() => setIsShareOpen(true), []);
  const closeShare = useCallback(() => setIsShareOpen(false), []);

  // Three cases where all three would be refused: a preview (a frozen snapshot),
  // someone else's workflow (live, in another tenant), and an INSTALLED
  // application - which does sit in the caller's own tenant, but whose plan the
  // backend freezes.
  if (isPreviewOnly || !canEdit) return null;

  return (
    <>
      <Button
        variant="ghost"
        size="sm"
        onClick={openShare}
        title={t('actions.share')}
        aria-label={t('actions.share')}
        data-testid="panel-action-share"
        className="h-8 w-8 p-0"
      >
        <Globe className="w-4 h-4" />
      </Button>

      <WorkflowSaveWithVersions
        workflowId={workflowId}
        saveStatus={saveStatus}
        isDirty={isDirty}
        isAgentStreaming={isAgentStreaming}
        isRunMode={isRunMode}
        onSave={requestSave}
        /* Icon-only: the bar shares its row with the sub-tabs. */
        desktop={false}
        /* The bar is the panel's last row, so the version list opens upward. */
        placement="above"
      />

      {/* Run is edit-mode only, like the header: in run mode the canvas toolbar
          owns firing a trigger, and the Run sub-tab owns the run itself. Hidden
          for a read-only VIEWER, whose execute would 403 (the endpoint saves the
          plan first). */}
      {!isRunMode && canMutate && (
        <div data-testid="panel-action-run">
          <WorkflowRunSplitButton workflowId={workflowId} desktop={false} />
        </div>
      )}

      {/* Mounted only once asked for. A closed wizard renders nothing, but it
          still subscribes and holds state, and this bar is mounted per panel tab
          for the life of the session - a dialog most tabs never open. */}
      {isShareOpen && (
        <PublishWorkflowModal
          isOpen
          onClose={closeShare}
          workflowId={workflowId}
          workflowName={metadata?.name ?? ''}
          workflowDescription={metadata?.description ?? ''}
        />
      )}
    </>
  );
}
