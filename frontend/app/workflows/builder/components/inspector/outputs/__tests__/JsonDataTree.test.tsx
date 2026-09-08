// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="spinner" />,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  isFileRef: (value: unknown) =>
    !!value && typeof value === 'object' && (value as Record<string, unknown>)._type === 'file',
  normalizeFileRef: (value: Record<string, unknown>) =>
    value && value.file_url
      ? { _type: 'file', path: value.file_url, name: value.file_name, mimeType: 'application/pdf', size: 10 }
      : value,
  getFilePath: (value: Record<string, unknown>) => value.path,
  fileRefToUrl: () => null,
  fileService: { downloadAndSave: vi.fn(), formatFileSize: () => '1 kB' },
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));

import { JsonValueTree, PrimitiveValue } from '../JsonDataTree';
import { LONG_STRING_CHARS } from '../runValueUtils';

function Tree({ data, labelForKey }: { data: unknown; labelForKey?: (k: string) => string }) {
  const [expanded, setExpanded] = React.useState<Set<string>>(new Set());
  return (
    <JsonValueTree
      data={data}
      path={[]}
      expandedPaths={expanded}
      onToggleExpand={(p) =>
        setExpanded((prev) => {
          const next = new Set(prev);
          if (next.has(p)) next.delete(p);
          else next.add(p);
          return next;
        })
      }
      labelForKey={labelForKey}
    />
  );
}

describe('PrimitiveValue', () => {
  it('calls out a value the engine failed to resolve instead of showing it as data', () => {
    render(<PrimitiveValue value="INVALID_TEMPLATE: nope" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
    expect(screen.getByTestId('run-value-unresolved').textContent).toContain('INVALID_TEMPLATE');
  });

  it('leaves an ordinary string alone', () => {
    render(<PrimitiveValue value="https://example.com" />);
    expect(screen.queryByTestId('run-value-unresolved-badge')).toBeNull();
  });

  it('offers a JSON string as an expandable tree rather than one escaped line', () => {
    render(<PrimitiveValue value='{"status":"ok","items":[1,2]}' />);
    const toggle = screen.getByTestId('run-value-json-toggle');
    expect(toggle).toBeTruthy();

    fireEvent.click(toggle);
    expect(screen.getByText('status')).toBeTruthy();
    expect(screen.getByText('"ok"')).toBeTruthy();
  });

  it('shows a configured expression as the value it is, with no JSON view and no alarm', () => {
    // Several nodes echo their expression on purpose (FilterNode reports its
    // "input" that way); badging it as unresolved would cry wolf.
    render(<PrimitiveValue value="{{core:x.output}}" />);
    expect(screen.queryByTestId('run-value-json-toggle')).toBeNull();
    expect(screen.queryByTestId('run-value-unresolved-badge')).toBeNull();
  });

  it('calls out the engine variable-not-found marker, which IS a failure', () => {
    render(<PrimitiveValue value="{{__UNRESOLVED__:mcp:step.output.url}}" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
  });

  it('clamps a long string behind an explicit expand', () => {
    const long = 'x'.repeat(LONG_STRING_CHARS + 50);
    render(<PrimitiveValue value={long} />);
    const toggle = screen.getByTestId('run-value-show-more');
    expect(document.body.textContent).toContain('…');

    fireEvent.click(toggle);
    expect(document.body.textContent).toContain(long);
  });

  it('shows short strings whole, with no expand control', () => {
    render(<PrimitiveValue value="short" />);
    expect(screen.queryByTestId('run-value-show-more')).toBeNull();
  });
});

describe('JsonValueTree', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('relabels TOP-LEVEL keys only, so a nested key still matches its drag path', () => {
    render(
      <Tree
        data={{ duration: 1000, nested: { duration: 5 } }}
        labelForKey={(key) => (key === 'duration' ? 'Duration (ms)' : key)}
      />,
    );
    expect(screen.getByText('Duration (ms)')).toBeTruthy();

    fireEvent.click(screen.getByText('nested'));
    // The nested key keeps its raw name.
    expect(screen.getByText('duration')).toBeTruthy();
  });

  it('copies a row value as text, not as a quoted JSON string', async () => {
    render(<Tree data={{ url: 'https://example.com' }} />);
    const copyButtons = screen.getAllByRole('button', { name: 'copyValue' });
    fireEvent.click(copyButtons[0]);
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('https://example.com');
  });

  it('copies an object row as pretty JSON', () => {
    render(<Tree data={{ payload: { a: 1 } }} />);
    const copyButtons = screen.getAllByRole('button', { name: 'copyValue' });
    fireEvent.click(copyButtons[0]);
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('{\n  "a": 1\n}');
  });

});

describe('JsonValueTree - file references', () => {
  const FILE = { _type: 'file', path: 'tenant/a.pdf', name: 'a.pdf', mimeType: 'application/pdf', size: 1024 };

  it('renders a file VALUE with its view and download actions instead of a plain object', () => {
    render(<Tree data={{ report: FILE }} />);
    expect(screen.getByRole('button', { name: /viewFile/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /downloadFile/i })).toBeTruthy();
  });

  it('expands a file value into the properties a downstream node reads', () => {
    render(<Tree data={{ report: FILE }} />);
    fireEvent.click(screen.getByText('report'));
    expect(screen.getByText('path')).toBeTruthy();
    expect(screen.getByText('mimeType')).toBeTruthy();
    expect(screen.getByText('"tenant/a.pdf"')).toBeTruthy();
  });

  it('renders a payload that IS a file reference, with no key to hang it on', () => {
    render(<Tree data={FILE} />);
    expect(screen.getByText('file')).toBeTruthy();
    expect(screen.getByRole('button', { name: /downloadFile/i })).toBeTruthy();
  });

  it('puts a preview card above the fields of a flat file-carrying step output', () => {
    render(
      <Tree
        data={{ _status: 'COMPLETED', file_url: '/files/a.pdf', file_name: 'a.pdf', extra: 1 }}
      />,
    );
    // The card names the file, and the ordinary fields are still listed below it.
    expect(screen.getAllByText('a.pdf').length).toBeGreaterThan(0);
    expect(screen.getByText('extra')).toBeTruthy();
  });
});

describe('JsonValueTree - empty payloads', () => {
  it('renders an empty object as itself', () => {
    const { container } = render(<Tree data={{}} />);
    expect(container.textContent).toContain('{}');
  });

  it('renders an empty array as itself', () => {
    const { container } = render(<Tree data={[]} />);
    expect(container.textContent).toContain('[]');
  });

  it('renders a null payload as null rather than as nothing', () => {
    const { container } = render(<Tree data={null} />);
    expect(container.textContent).toContain('null');
  });
});

describe('PrimitiveValue - the other unresolved kinds', () => {
  it('calls out a value that IS the stringified object', () => {
    render(<PrimitiveValue value="[object Object]" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
    expect(screen.getByTestId('run-value-unresolved').textContent).toContain('[object Object]');
  });

  it('badges a value that CONCATENATED an object into text', () => {
    // Characterization of the long-standing `includes` matcher, pinned because a
    // round of this work briefly narrowed it to equality. The shape a run actually
    // produces is a set assignment whose expression resolved to a map; rendering
    // it as ordinary text is how the reader ends up debugging the wrong node.
    render(<PrimitiveValue value="Owner: [object Object]" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
  });

  it('clamps an unresolved value too, so a huge broken template cannot flood the column', () => {
    render(<PrimitiveValue value={`INVALID_TEMPLATE: ${'x'.repeat(LONG_STRING_CHARS + 20)}`} />);
    expect(screen.getByTestId('run-value-show-more')).toBeTruthy();
    expect(screen.getByTestId('run-value-unresolved').textContent).toContain('…');
  });
});
