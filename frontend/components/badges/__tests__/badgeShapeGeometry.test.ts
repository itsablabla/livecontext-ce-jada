import { describe, it, expect } from 'vitest';
import { shapePath, shapeInnerPath } from '../badgeVisuals';

/**
 * The medal is a metal frame around a coloured disc, and the whole illusion
 * rests on that frame being the same thickness the whole way round. Nothing
 * about a wrong inner contour throws or logs: the medal simply comes out
 * looking lopsided, which is how the gem shipped with a frame ~11 units thick
 * across its flat table and ~7 along its pavilion.
 *
 * <p>These tests measure the frame instead of trusting the transform.
 */

type Point = [number, number];

/** Vertices of a straight-line polygon path (`M x y L x y ... Z`). */
function polygon(path: string): Point[] {
  const numbers = path.match(/-?\d+(\.\d+)?/g)?.map(Number) ?? [];
  const points: Point[] = [];
  for (let i = 0; i + 1 < numbers.length; i += 2) points.push([numbers[i], numbers[i + 1]]);
  return points;
}

/** Perpendicular distance from a point to the infinite line through a->b. */
function distanceToLine(point: Point, a: Point, b: Point): number {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const length = Math.hypot(dx, dy);
  return Math.abs((point[0] - a[0]) * dy - (point[1] - a[1]) * dx) / length;
}

describe('medal frame geometry', () => {
  it('insets the gem edge by edge, keeping the frame one thickness all round', () => {
    const outer = polygon(shapePath('gem'));
    const inner = polygon(shapeInnerPath('gem') as string);

    expect(outer).toHaveLength(5);
    expect(inner).toHaveLength(5);

    // Edge i of the inner contour must be parallel to edge i of the outer one
    // and the same distance from it. Measuring BOTH endpoints of each inner edge
    // against the outer line catches a contour that merely happens to touch the
    // right distance at one corner.
    const gaps: number[] = [];
    for (let i = 0; i < outer.length; i++) {
      const a = outer[i];
      const b = outer[(i + 1) % outer.length];
      gaps.push(distanceToLine(inner[i], a, b));
      gaps.push(distanceToLine(inner[(i + 1) % inner.length], a, b));
    }

    const min = Math.min(...gaps);
    const max = Math.max(...gaps);
    expect(min).toBeGreaterThan(9);
    expect(max - min).toBeLessThan(0.5);
  });

  it('proves the uniform scale it replaced could not have held that frame', () => {
    // Guards against someone "simplifying" the gem back to the shared transform.
    // Scaling about the centre leaves the pavilion frame a third thinner than
    // the table, which is exactly the defect the explicit contour fixes.
    const outer = polygon(shapePath('gem'));
    const scaled: Point[] = outer.map(([x, y]) => [50 + (x - 50) * 0.74, 50 + (y - 50) * 0.74]);

    const gaps = outer.map((a, i) =>
      distanceToLine(scaled[i], a, outer[(i + 1) % outer.length]));

    expect(Math.max(...gaps) - Math.min(...gaps)).toBeGreaterThan(2);
  });

  it('leaves the centred silhouettes on the shared scale, which already traces them', () => {
    // hexagon and rosette are radially uniform, so the transform IS a parallel
    // offset for them; declaring a hand-authored contour would only add a second
    // thing to keep in sync.
    expect(shapeInnerPath('hexagon')).toBeNull();
    expect(shapeInnerPath('rosette')).toBeNull();
    expect(shapeInnerPath('shield')).toBeNull();
  });

  it('keeps the gem inner contour inside the outer one and inside the viewBox', () => {
    const inner = polygon(shapeInnerPath('gem') as string);
    const xs = inner.map((p) => p[0]);
    const ys = inner.map((p) => p[1]);

    expect(Math.min(...xs)).toBeGreaterThan(5);
    expect(Math.max(...xs)).toBeLessThan(95);
    expect(Math.min(...ys)).toBeGreaterThan(9);
    expect(Math.max(...ys)).toBeLessThan(96);
  });

  it('keeps the gem symmetric about the vertical axis, like the medal it frames', () => {
    for (const path of [shapePath('gem'), shapeInnerPath('gem') as string]) {
      const xs = polygon(path).map((p) => p[0]);
      const mirrored = xs.map((x) => 100 - x).sort((a, b) => a - b);
      const sorted = [...xs].sort((a, b) => a - b);
      sorted.forEach((x, i) => expect(Math.abs(x - mirrored[i])).toBeLessThan(0.05));
    }
  });
});
