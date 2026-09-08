/**
 * REACHABLE FOR EXACTLY ONE PATH: `/api/proxy/external-proxy`.
 *
 * Despite the catch-all segment, these handlers do NOT serve general `/api/proxy/*` traffic.
 * `proxy.ts` (the Next middleware) matches `/api/proxy/:path*` and `NextResponse.rewrite()`s
 * every path straight to the gateway; the one exception is `external-proxy`, which it lets
 * through with `NextResponse.next()` so the request lands here and is handed to the LOCAL
 * `/api/external-proxy` handler, called IN PROCESS (`callExternalProxyLocally` below - it used
 * to be re-fetched over the public origin, which the cloud ingress answers with the gateway's
 * 404). That bypass is deliberate and pinned by
 * `e2e/ce/ce-routing-middleware-ui.spec.ts` ("/api/proxy/external-proxy bypasses proxy
 * rewriting").
 *
 * And `external-proxy` itself takes an early return that re-routes it to the local route before
 * reaching the gateway-forwarding block. So everything after that early return (header
 * filtering, `?token` promotion, binary handling) runs for NOTHING: it is dead. Two
 * consequences, both of which have already cost real time:
 *
 * 1. **A proxy bug fixed HERE ships nothing.** The live copy is `proxy.ts`. The `?token`
 *    stripping fix (`cec80f7d7`) had to be applied to both for that reason, and only the
 *    `proxy.ts` half of it ever ran. Fix `proxy.ts`; there is no case where mirroring it into
 *    the dead block below has an effect.
 * 2. **This file cannot be instrumented to observe a stalled request.** Measured 2026-08-07 on
 *    a CE container: a proxied request produced zero lines from here because the handler never
 *    ran. Timing out or logging general proxy traffic has to happen in `proxy.ts`, and a
 *    middleware rewrite exposes no hook for either.
 */
import { NextRequest, NextResponse } from 'next/server';
import { isJwtShapedToken } from '@/lib/utils/jwtShape';
import { POST as externalProxyPost } from '@/app/api/external-proxy/route';

const GATEWAY_URL = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';
const REDACTED_QUERY_KEYS = new Set([
  'authorization',
  'code',
  'id_token',
  'refresh_token',
  'token',
  'access_token',
]);

// Next.js 15 App Router route segment config
export const maxDuration = 60;
export const dynamic = 'force-dynamic';

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'GET');
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'POST');
}

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'PUT');
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'DELETE');
}

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'PATCH');
}

async function handleRequest(
  request: NextRequest,
  pathSegments: string[],
  method: string
) {
  const startedAt = performance.now();
  const requestId = request.headers.get('x-request-id') || crypto.randomUUID();
  const path = pathSegments.join('/');
  const loggedPath = buildLoggedPath(path, request.nextUrl.searchParams);

  try {
    // external-proxy is served by the LOCAL route handler, invoked IN PROCESS.
    if (path === 'external-proxy') {
      const response = await callExternalProxyLocally(request, method, requestId);
      const responseBody = await response.text();
      logProxyResult(method, loggedPath, response.status, startedAt, requestId, responseBody.length);

      return new NextResponse(responseBody, {
        status: response.status,
        statusText: response.statusText,
        headers: createResponseHeaders(requestId, 'application/json'),
      });
    }
    
    // Pour les autres routes, utiliser le Gateway
    // Toutes les routes passent par /api/ prefix
    const targetUrl = `${GATEWAY_URL}/api/${path}`;
    
    // Recuperer les parametres de requete
    const searchParams = new URLSearchParams(request.nextUrl.searchParams);

    // Recuperer le token d'authentification depuis l'Authorization header
    // Fallback: extraire depuis le query param 'token' (pour <img>, window.open, etc. qui ne peuvent pas envoyer de headers)
    const authHeader = request.headers.get('authorization');
    // ShareToken is forwarded as-is; Bearer tokens are extracted
    const isShareToken = authHeader?.startsWith('ShareToken ') ?? false;
    let accessToken = authHeader?.startsWith('Bearer ') ? authHeader.substring(7) : null;
    // Only hijack a `token` query param as auth when it is actually a JWT access token.
    // Otherwise it is a RESOURCE token the backend itself reads from ?token= (the
    // invitation-accept lookup, email verification, password reset, ...) and it MUST
    // reach the gateway untouched. A raw access token is always a JWT; resource tokens
    // are opaque/UUID, so this never strips them. (Without this guard the proxy deleted
    // the invitation token, so /organizations/invitations/info?token= always 404'd.)
    if (!accessToken && searchParams.has('token') && isJwtShapedToken(searchParams.get('token'))) {
      accessToken = searchParams.get('token');
      searchParams.delete('token'); // ne pas transmettre le token brut au Gateway
    }

    const queryString = searchParams.toString();
    const fullUrl = queryString ? `${targetUrl}?${queryString}` : targetUrl;

    // Preparer les headers
    const contentType = request.headers.get('content-type') || 'application/json';
    const isMultipart = contentType.includes('multipart/form-data');

    const headers: HeadersInit = {
      'Content-Type': contentType,
      'X-Request-Id': requestId,
    };

    // Forward Accept header for content negotiation (e.g., JSON vs streaming for /v3/chat)
    const acceptHeader = request.headers.get('accept');
    if (acceptHeader) {
      headers['Accept'] = acceptHeader;
    }

    const activeOrganizationId = request.headers.get('x-active-organization-id');
    if (activeOrganizationId) {
      headers['X-Active-Organization-ID'] = activeOrganizationId;
    }

    if (process.env.NODE_ENV !== 'production') {
      const forwardedFor = request.headers.get('x-forwarded-for');
      if (forwardedFor) {
        headers['X-Forwarded-For'] = forwardedFor;
      }
    }

    // Ajouter l'Authorization header si le token est present
    if (isShareToken && authHeader) {
      headers['Authorization'] = authHeader; // forward ShareToken <token> as-is
    } else if (accessToken) {
      headers['Authorization'] = `Bearer ${accessToken}`;
    }

    // Prepare body
    let body: BodyInit | undefined;
    if (method !== 'GET') {
      if (isMultipart) {
        // For multipart, stream the body directly to preserve structure
        body = request.body ?? undefined;
      } else {
        body = await request.text();
      }
    }

    // Faire la requete vers le Gateway
    const response = await fetch(fullUrl, {
      method,
      headers,
      body,
      // @ts-expect-error - duplex is needed for streaming body
      duplex: 'half',
    });

    // Creer la reponse avec les headers CORS
    const responseHeaders = createResponseHeaders(requestId);

    // Copier les headers de la reponse du Gateway
    // Skip content-encoding/content-length: Node.js fetch auto-decompresses,
    // so the body is already decoded - forwarding these would cause ERR_CONTENT_DECODING_FAILED
    const skipHeaders = new Set(['content-encoding', 'content-length', 'transfer-encoding', 'x-request-id']);
    response.headers.forEach((value, key) => {
      const lk = key.toLowerCase();
      if (!lk.startsWith('access-control-') && !skipHeaders.has(lk)) {
        responseHeaders.set(key, value);
      }
    });

    // Gestion speciale pour les codes 204 (No Content) et 205 (Reset Content)
    if (response.status === 204 || response.status === 205) {
      logProxyResult(method, loggedPath, response.status, startedAt, requestId, 0);
      return new NextResponse(null, {
        status: response.status,
        statusText: response.statusText,
        headers: responseHeaders,
      });
    }

    // Pour les fichiers binaires (images, PDFs, Excel, etc.), utiliser arrayBuffer
    const responseContentType = response.headers.get('content-type');
    const isBinary = responseContentType && (
      responseContentType.startsWith('image/') ||
      responseContentType.startsWith('audio/') ||
      responseContentType.startsWith('video/') ||
      responseContentType.includes('octet-stream') ||
      responseContentType.includes('pdf') ||
      responseContentType.includes('zip') ||
      responseContentType.includes('spreadsheetml') ||
      responseContentType.includes('vnd.ms-excel')
    );

    if (isBinary) {
      const binaryData = await response.arrayBuffer();
      logProxyResult(method, loggedPath, response.status, startedAt, requestId, binaryData.byteLength);
      return new NextResponse(binaryData, {
        status: response.status,
        statusText: response.statusText,
        headers: responseHeaders,
      });
    }

    // Pour le texte/JSON, lire comme texte
    const responseBody = await response.text();
    logProxyResult(method, loggedPath, response.status, startedAt, requestId, responseBody.length);

    return new NextResponse(responseBody, {
      status: response.status,
      statusText: response.statusText,
      headers: responseHeaders,
    });

  } catch (error) {
    const durationMs = Math.round(performance.now() - startedAt);
    console.error(`[Proxy] ${method} ${loggedPath} failed durationMs=${durationMs} requestId=${requestId}`, error);
    return NextResponse.json(
      { error: 'Proxy error', message: error instanceof Error ? error.message : 'Unknown error' },
      { status: 500, headers: createResponseHeaders(requestId, 'application/json') }
    );
  }
}

/**
 * Runs the local `/api/external-proxy` handler in this process instead of re-fetching it
 * over `request.nextUrl.origin`.
 *
 * The origin round trip worked in CE, where Next serves every path itself, and failed in
 * cloud, where the ingress sends `/api` to the GATEWAY: the hop left the pod, came back as
 * the gateway's own 404, and every external call from the MCP Test tab failed there while
 * passing in CE and in every test. Verified against production on 2026-09-05.
 *
 * Calling the handler directly is the fix that does not widen anything: `/api/external-proxy`
 * stays unreachable from the internet (its only caller is this route, which IS carved out),
 * so the SSRF-guarded fetcher never becomes a public surface. It also drops a full network
 * round trip through Cloudflare from every MCP test call.
 */
async function callExternalProxyLocally(
  request: NextRequest,
  method: string,
  requestId: string,
): Promise<Response> {
  if (method !== 'POST') {
    // The local route exports POST only. Over the network Next answered 405 for anything
    // else, so answer the same rather than inventing a new contract for this path.
    return NextResponse.json(
      { error: 'Method Not Allowed' },
      { status: 405, headers: { Allow: 'POST' } },
    );
  }

  const url = new URL('/api/external-proxy', request.nextUrl.origin);
  url.search = request.nextUrl.search;

  // Same three headers the network hop forwarded, and only those: the handler authenticates
  // the caller from Authorization, and everything else it needs is in the body.
  const forwarded = new NextRequest(url, {
    method: 'POST',
    headers: {
      'Content-Type': request.headers.get('content-type') || 'application/json',
      Authorization: request.headers.get('authorization') || '',
      'X-Request-Id': requestId,
    },
    body: await request.text(),
  });

  return externalProxyPost(forwarded);
}

function createResponseHeaders(requestId: string, contentType?: string): Headers {
  const responseHeaders = new Headers();
  responseHeaders.set('Access-Control-Allow-Origin', '*');
  responseHeaders.set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS, PATCH');
  responseHeaders.set('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With, X-Request-Id, X-Active-Organization-ID');
  // No Access-Control-Allow-Credentials: the wildcard origin above is invalid alongside
  // credentialed CORS (browsers reject the pair), and auth is Bearer-token in the
  // Authorization header, never an ambient cookie, so credentials mode is not needed.
  responseHeaders.set('Access-Control-Expose-Headers', 'X-Request-Id');
  responseHeaders.set('X-Request-Id', requestId);
  if (contentType) {
    responseHeaders.set('Content-Type', contentType);
  }
  return responseHeaders;
}

function buildLoggedPath(path: string, searchParams: URLSearchParams): string {
  const sanitized = new URLSearchParams(searchParams);
  for (const key of Array.from(sanitized.keys())) {
    if (REDACTED_QUERY_KEYS.has(key.toLowerCase())) {
      sanitized.set(key, 'redacted');
    }
  }
  const queryString = sanitized.toString();
  return queryString ? `${path}?${queryString}` : path;
}

function logProxyResult(
  method: string,
  path: string,
  status: number,
  startedAt: number,
  requestId: string,
  responseLength: number
): void {
  const durationMs = Math.round(performance.now() - startedAt);
  console.info(`[Proxy] ${method} ${path} status=${status} durationMs=${durationMs} responseLength=${responseLength} requestId=${requestId}`);
}
