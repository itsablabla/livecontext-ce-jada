// @vitest-environment jsdom
/**
 * How a variable row splits colour between its two halves.
 *
 * The row is the variable on the left and its type on the right, and only one of
 * them should carry a filled block. The TYPE gets the chip: it is a small closed
 * vocabulary (text / number / object / ...), so a colour block is what lets the
 * eye sort a long list without reading it, and it matches the chip the Output
 * column's schema trees already use. The VARIABLE does not: a row is nothing but
 * the token, so tinting it paints the whole line and reads as a selected state
 * the user never chose. It keeps the expression COLOUR, which is what says "this
 * is an expression", and nothing else.
 *
 * Both halves are asserted here because the previous arrangement had them the
 * other way round, and either one drifting back is invisible without a test.
 */
import { describe, it, expect, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { GlobalVariablesInspector } from '../GlobalVariablesInspector';
import { getFieldTypeColor } from '../../../types';

afterEach(cleanup);

const VARIABLES = [
  { name: 'ee', label: '{{$vars.ee}}', type: 'text', path: '{{$vars.ee}}', expressionToken: true },
  {
    name: 'current_item',
    label: 'Current Item',
    type: 'object',
    path: '{{core:split.output.current_item}}',
    properties: [{ name: 'id', label: 'id', type: 'number', path: '{{core:split.output.current_item.id}}' }],
  },
];

describe('GlobalVariablesInspector row styling', () => {
  it('gives the type a filled chip, the same one the Output column uses', () => {
    render(<GlobalVariablesInspector variables={VARIABLES as any} />);

    const labels = screen.getAllByText(/^(text|object)$/);
    expect(labels.length).toBeGreaterThan(0);
    for (const label of labels) {
      expect(label.className, `type label lost its chip background: ${label.className}`)
        .toMatch(/(^|\s)bg-/);
      // Padding and radius are what make it read as a chip rather than tinted text.
      expect(label.className).toMatch(/px-1\.5/);
      expect(label.className).toMatch(/rounded/);
    }
  });

  it('colours the chip by type, so a long list sorts by eye', () => {
    render(<GlobalVariablesInspector variables={VARIABLES as any} />);
    const textLabel = screen.getByText('text');
    // Not a copy of the palette: the same helper the rest of the builder uses.
    for (const cls of getFieldTypeColor('text').split(' ')) {
      expect(textLabel.className).toContain(cls);
    }
  });

  it('leaves the variable token unfilled, with only the expression colour', () => {
    render(<GlobalVariablesInspector variables={VARIABLES as any} />);

    const token = screen.getByText('{{$vars.ee}}');
    // `.token-expression` paints `--expression-bg` behind the text, which is right
    // inside an expression editor and wrong on a row that IS the expression.
    expect(token.className, 'the variable row is tinted again').not.toContain('token-expression ');
    expect(token.className).toContain('token-expression-plain');
  });
});
