/**
 * @vitest-environment jsdom
 *
 * Wiring test: a staged image attachment in the composer shows a clickable
 * "enlarge" thumbnail that opens the image lightbox before the message is even sent.
 */
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import * as React from 'react';

// The Generate control leads to the studio through the LOCALE-AWARE router, and next-intl's
// navigation module cannot resolve 'next/navigation' under vitest. Stood in for here because this
// suite is not about where that control goes (that is pinned in the generate-entry-point suites).
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, prefetch: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: null }),
}));

vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));

vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: { getAgentByConversationId: vi.fn() },
}));

vi.mock('../AttachmentHandler', () => ({ AttachmentHandler: () => null }));

// Stage an image attachment with a fake local preview URL on file select.
vi.mock('@/lib/api/attachmentApi', () => ({
  attachmentApi: {
    isAllowedType: () => true,
    determineType: () => 'IMAGE',
    createPreviewUrl: () => 'blob:preview-123',
    revokePreviewUrl: vi.fn(),
    toAttachmentRefs: () => [],
  },
}));

import { MessageComposer } from '../MessageComposer';

describe('MessageComposer image lightbox', () => {
  it('opens the lightbox when the staged image preview is clicked', () => {
    const { container } = render(
      <MessageComposer
        inputValue=""
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    // No lightbox before any image is staged.
    expect(screen.queryByRole('dialog')).toBeNull();

    // Stage an image file via the hidden file input.
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['x'], 'screenshot.png', { type: 'image/png' });
    fireEvent.change(fileInput, { target: { files: [file] } });

    // The preview thumbnail is an "enlarge" button.
    const enlargeBtn = screen.getByLabelText('chat.imageViewer.enlarge');
    fireEvent.click(enlargeBtn);

    // Lightbox opens showing the same local preview URL (scope to the dialog - the
    // composer chip also renders a thumbnail with the same alt text).
    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeTruthy();
    const img = within(dialog).getByAltText('screenshot.png') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('blob:preview-123');
  });
});
