import { NextResponse } from 'next/server';
import { STATUS_REVALIDATE_SECONDS, fetchServiceStatus } from '@/lib/status/fetchStatus';
import { IS_CE } from '@/lib/edition/edition';

/**
 * `GET /api/status` - the single public read hop for service status.
 *
 * Unauthenticated by design (a user who cannot sign in is exactly who needs it),
 * and it never reaches the Java gateway: the whole point of the feature is that
 * the status path does not depend on the cluster it reports on. It reads the
 * probe file on ops-host over the VLAN and incident.io's Widget API, both cached
 * (see fetchStatus), so viewer count does not multiply upstream calls.
 *
 * Always 200 with a usable payload: `dataAvailable` and `incidentsSource` say
 * what could not be reached, and `overall` degrades to `unknown` rather than
 * quietly reporting "operational".
 */
// Always run: a GET route handler is otherwise PRERENDERED at build time, which
// would freeze a build-machine "nothing reachable" answer into the endpoint for
// the life of the image. Running per request costs nothing here, because the
// upstream calls themselves are memoised for the cache window (see fetchStatus).
export const dynamic = 'force-dynamic';

export async function GET() {
  // Cloud only, like /status and the in-app surfaces: a self-hosted install has
  // nothing to report here, and leaving the endpoint answering would offer a
  // surface with no consumer and no data behind it.
  if (IS_CE) return new NextResponse(null, { status: 404 });

  const status = await fetchServiceStatus();

  return NextResponse.json(status, {
    headers: {
      // Absorbed by any shared cache in front of us; the fetch-level cache in
      // fetchStatus already collapses concurrent renders.
      'Cache-Control': `public, s-maxage=${STATUS_REVALIDATE_SECONDS}, stale-while-revalidate=120`,
    },
  });
}
