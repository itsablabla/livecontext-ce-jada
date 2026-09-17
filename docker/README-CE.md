# LiveContext Community Edition - Docker Setup

CE ships as a single self-hosted monolith with embedded auth (no Keycloak). The cloud
SaaS edition runs a different topology (microservices + Keycloak) and is deployed via
GitHub Actions, not via this directory.

| Mode | File | Containers | Keycloak | Best for |
|------|------|-----------|----------|----------|
| **Monolith** | `docker-compose.yml` | 10 | No | Local dev, self-hosting |
| **Coolify with gateway** | `docker-compose.coolify.yml` | 11 | No | See [COOLIFY.md](COOLIFY.md) |

---

## Prerequisites

- Docker Desktop 4.x+ (or Docker Engine 24+ with Compose v2)
- AMD64 host; 12 GB RAM recommended for source builds and the full stack
- An LLM provider for agents: connect to LiveContext Cloud (recommended), or add your own OpenAI / Anthropic / Google key in the app

## Quick Start

```bash
# From the repo root. Build the fork's backend, frontend, and browser service:
docker compose up -d --build

# Wait ~2-3 minutes for the backend to initialize (Flyway migrations + tool registration)
docker compose ps
# Wait until the "livecontext" service is "healthy" and "frontend" is up.

# Open http://localhost:3000 and create an account (the first user becomes the admin)
```

> **Source build is required for Jada.** The main frontend and backend build from
> this fork; pulling upstream v0.2.15 images omits Jada's code changes. For a
> Coolify installation use [COOLIFY.md](COOLIFY.md), not the local host-port setup.

> **Accessing from another machine (not localhost)?** Works out of the box, nothing to
> rebuild. The web UI resolves the backend origin at runtime from the address you opened
> it with, so `http://192.168.1.50:3000` connects to `http://192.168.1.50:8080`. Just make
> sure BOTH ports are published and reachable. If the backend is not at
> `<the address you opened the app with>:BACKEND_PORT` (typically a reverse proxy serving
> everything on one origin), set `GATEWAY_PUBLIC_URL` on the `frontend` service to the
> browser-facing backend URL, e.g. `GATEWAY_PUBLIC_URL=https://livecontext.example.com`.

## Architecture

```
Browser (:3000)
   │
   ├── Static assets / SSR ──► Frontend (Next.js, host :3000 → container :3000)
   │                              │  SSR proxy: /api/proxy/* ──► Backend (container :8080)
   │
   └── WebSocket ────────────► Backend monolith (host :8080 → container :8080)
                                    ├── PostgreSQL (pgvector, :5432)
                                    ├── Redis (:6379)
                                    ├── MinIO S3 (:9000)
                                    └── Bridge (CLI/MCP tools, :8093)
```

### Containers

| Container | Image | Host port | Purpose |
|-----------|-------|-----------|---------|
| `livecontext-db` | `pgvector/pgvector:pg16` | 5432 (internal) | Database with vector extension |
| `livecontext-redis` | `redis:7-alpine` | 6379 (internal) | Cache, pub/sub, streaming |
| `livecontext-minio` | `minio/minio` | 9000 (internal) | S3-compatible file storage |
| `livecontext-minio-init` | `minio/mc` | - | Creates `workflow-files` bucket, then exits |
| `livecontext-bridge` | `ghcr.io/livecontext-ai/livecontext-ce-bridge` | 8093 (internal) | CLI adapters + MCP tools |
| `livecontext-app` | `ghcr.io/livecontext-ai/livecontext-ce` | **8080** | All backend services in one JAR |
| `livecontext-frontend` | `ghcr.io/livecontext-ai/livecontext-ce-frontend` | **3000** | Next.js app (embedded auth) |

Only ports **3000** (frontend, the app) and **8080** (backend API) are exposed to the host.

## Configuration

### Environment Variables

Pass them inline or copy `docker/.env.ce.example` to `docker/.env.ce` and run Compose with
`--env-file docker/.env.ce`.

```bash
# docker/.env.ce

# LLM API keys - at least one required for agent execution
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GOOGLE_API_KEY=AI...

# Database (defaults are fine for local dev)
DB_USERNAME=postgres
DB_PASSWORD=postgres

# MinIO (defaults are fine for local dev)
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin

# Security - leave blank for first-boot auto-generation
CREDENTIAL_ENCRYPTION_PASSWORD=
CREDENTIAL_ENCRYPTION_SALT=

# Ports for direct Docker installs. Browser-facing origins resolve at runtime;
# set PUBLIC_BASE_URL and GATEWAY_PUBLIC_URL for a reverse-proxied server.
BACKEND_PORT=8080
FRONTEND_PORT=3000
```

### What the Backend Handles

The monolith JAR bundles all microservices into one process with the `ce` Spring profile:

- **Embedded auth** (email/password) - no Keycloak needed
- **Flyway migrations** - DB schema created automatically on first boot
- **All service endpoints** on a single port (orchestrator, agent, auth, catalog, etc.)
- **S3 storage** via MinIO for workflow file nodes
- **Redis** for event bus, cache, and streaming state
- **Unlimited credits** - consumption is tracked but balance is infinite

### What the Frontend Handles

The Next.js app builds with `NEXT_PUBLIC_APP_EDITION=ce` (and the legacy
`NEXT_PUBLIC_AUTH_MODE=embedded` for one-release backward compat), which:

- Uses the built-in login/register page (no Keycloak redirect)
- Proxies all API calls through `/api/proxy/*` to the backend container
- SSR pages are rendered server-side using the `http://livecontext:8080` internal Docker hostname
- Bypasses the marketing landing page - `/` (and `/{locale}`) redirect 308 to `/app/chat`
- Sets `robots.txt` to disallow all paths so the self-hosted instance never appears in public search results

## Build Details

### Backend Dockerfile (`backend/monolith-service/Dockerfile`)

Multi-stage Maven build:

1. **Build stage**: `maven:3.9-eclipse-temurin-21` - copies all module POMs, downloads dependencies (cached layer), then builds with `-Pce -DskipTests`
2. **Runtime stage**: `eclipse-temurin:21-jre-alpine` - copies only the fat JAR (`app.jar`), runs as non-root `livecontext` user

The `-Pce` Maven profile is critical: it makes all service modules produce regular JARs (not Spring Boot fat JARs), so the monolith can include them on its classpath. Only `monolith-service` gets repackaged as a Spring Boot executable JAR.

JVM settings: `-Xms512m -Xmx1024m -XX:+UseZGC -XX:+ZGenerational` (1.5 GB container limit).

### Frontend Dockerfile (`frontend/Dockerfile`)

Multi-stage Node.js build:

1. **Build stage**: `node:20-alpine` - installs deps, builds with `NEXT_PUBLIC_*` build args baked in
2. **Runtime stage**: `node:20-alpine` - copies standalone output + static assets + `messages/` (i18n locale files)

Key build args injected by docker-compose:

| Arg | Value | Purpose |
|-----|-------|---------|
| `NEXT_PUBLIC_APP_EDITION` | `ce` | Edition SSOT - drives landing bypass, robots.txt disallow, edition-aware UI |
| `NEXT_PUBLIC_AUTH_MODE` | `embedded` | Use built-in auth (not Keycloak). Kept as legacy shim for one release |
| `NEXT_PUBLIC_SPRING_BASE_URL` | `http://livecontext:8080` | Backend URL for SSR proxy (container-to-container) |
| `NEXT_PUBLIC_GATEWAY_WS_URL` | `http://localhost:8080` | Build-time fallback for the browser-facing backend URL. Inlined into the client bundle, so it is only a last resort now: the running app prefers the RUNTIME values below. Leave it alone unless you build your own image. |

**Runtime** env vars on the `frontend` service (no rebuild needed, this is how you serve CE
anywhere other than localhost):

| Variable | Default | Purpose |
|----------|---------|---------|
| `GATEWAY_PUBLIC_URL` | empty | Browser-facing backend origin. Empty means "derive it from the address the app was opened with, on `BACKEND_PORT`", which is what makes an install reachable by LAN IP or domain work unmodified. Set it when the backend is elsewhere, e.g. a reverse proxy on one origin: `https://livecontext.example.com`. |
| `BACKEND_PORT` | `8080` | Port the backend is published on, used for that derivation. Keep it equal to the port mapping on the `livecontext` service. |

### next.config.mjs - `compress: false`

Next.js compression is disabled. This is required for Docker Desktop on Windows (WSL2 backend) - the WSL2 port proxy fails to forward large chunked/gzipped SSR responses. In production, use a reverse proxy (nginx/Caddy) for compression.

### application-ce.yml - Key Settings

| Setting | Value | Why |
|---------|-------|-----|
| `deployment.mode` | `monolith` | Disables gateway auth filter, uses monolith security |
| `auth.mode` | `embedded` | Enables JWT key pair manager + password auth |
| `spring.flyway.enabled` | `true` | Auto-creates all DB schemas on first boot |
| `spring.flyway.baseline-on-migrate` | `true` | Safe start on empty or existing DB |
| `hikari.connection-init-sql` | `SET search_path TO orchestrator,auth,...` | All schemas accessible without prefixes |
| `piston.embedded` | `true` | In-process code execution (no Piston container; CE image includes bash, Node.js, Python, and tsx) |
| `websearch.enabled` | `true` (env `WEBSEARCH_ENABLED`) | Browser agent enabled by default in the repository Docker Compose stack |
| `credit.unlimited` | `true` | No billing, infinite credits |
| All `services.*-url` | `http://localhost:${PORT}` | Loopback - all services in same JVM |

## Browser agent (agent_browse) - enabled by default

The browser agent (an LLM that drives a real Chromium to navigate, click, and
extract from web pages) is **enabled by default** in the repository Docker
Compose stack. It needs a heavy Chromium + browser-use container (~1 GB image,
+2 GB shared memory), so the first `docker compose up -d` also builds the
bundled `websearch-service/` image from source:

```bash
docker compose up -d
```

- **Model:** the agent node picks the model per AI provider
  (google/anthropic/openai/deepseek/mistral/...). When the install is
  **cloud-linked**, the browser agent relays its per-step LLM calls through your
  cloud connection and bills the cloud account, exactly like the chat / workflow
  agents and `web_search` (no local key needed). **Not linked?** Add that
  provider's API key in the app (Settings > AI providers), or set the matching
  env key (e.g. `GEMINI_API_KEY` for Google); otherwise the run fails with the
  provider's "No API key" error.
- **web_search:** the same default stack also starts a **SearXNG**
  metasearch sidecar, wired via `WEBSEARCH_SEARXNG_URL`, so `web_search` returns
  results. Its config (kept engines + JSON output) is mounted read-only from
  `searxng/settings.yml`; set a unique `server.secret_key` there for your install.
- **Live view:** the side panel always shows the **final page** the agent saw
  (captured screenshot). The real-time screencast additionally needs
  `WEBSEARCH_CDP_JWT_SECRET` set to the same value on both the app and the
  `websearch` container.
- The legacy `docker/.env.ce.browser-agent` file remains compatible, but it is
  no longer required for the repository compose stack.

## Interface screenshots + PDF renderer - enabled by default

Interface nodes can render a page to a **PNG screenshot** (`generateScreenshot`)
or a **PDF** (`generatePdf`). That needs a headless Playwright/Chromium sidecar
(~1 GB image), and the repository Docker Compose stack starts it by default:

```bash
docker compose up -d
```

- **Best-effort when off:** with the renderer disabled the interface node still
  runs, it just emits no screenshot/PDF output - the rest of the workflow is
  unaffected.
- The legacy `docker/.env.ce.renderer` file remains compatible, but it is no
  longer required for the repository compose stack.

## Update check and anonymous install count

Once a day (and once shortly after startup) your install asks
`https://livecontext.ai/api/ce/releases/latest` whether a newer release exists.
That is what puts the "Update available" badge on the Settings > Information
card. The app never updates itself: the badge only shows you the
`docker compose pull` commands.

**What that request carries, beyond the HTTP basics** (host, accept, connection and a
default `User-Agent` naming the Java runtime, as any HTTP client sends)**:**

```
GET /api/ce/releases/latest?current=0.2.13
X-LiveContext-Anon-Install-Id: 8f2c1a44-...   # random UUID, generated once at first boot
```

The install id is a random UUID generated once and kept in your own database
(`auth.ce_install`). It is derived from nothing: not your IP, not your hostname,
not your licence, not any user account. It exists so the number of live
self-hosted installs can be counted, so the cloud stores it too, alongside
exactly three things: the version above and the dates it was first and last
seen. That is the whole record. **No IP address is stored in it**, and it is
deliberately not the cloud-link install id, so the record itself carries no link
or account information. A build made from source reports itself as `dev` rather
than by its commit id. Records not seen for 180 days are deleted.

To be precise about what that does and does not promise: like any HTTP request
to any service, this one reaches our edge with your IP visible to the web server
and its access log, exactly as your browser does when you open livecontext.ai.
What the claim above is about is the fleet record itself, which is the only
thing derived from this feature and the only thing it keeps.

**Turning it off**, in `docker/.env.ce`:

```bash
# Keep the update check, stop identifying this install:
CE_VERSIONCHECK_SENDINSTALLID=false

# Or drop the request entirely (no update badge either):
CE_VERSIONCHECK_ENABLED=false
```

Both are read at startup, so restart the backend after changing them. With the
check off, nothing at all leaves your install on this path, and every feature
keeps working.

## What's new panel

After an upgrade, a one-time panel describes the newest change in the version
you are running, illustrated with an image or a short clip. It ships **inside
the image**: no feed is polled and no request leaves your install, so it works
the same on an air-gapped box as on a connected one. Each user sees it once,
older entries are never replayed, and it stays reachable afterwards from the
"What's new" entry in the profile menu.

To remove it entirely, in `docker/.env.ce`:

```bash
CHANGELOG_ENABLED=false
```

Read at startup, so restart the backend after changing it. With it off there is
no panel, no profile-menu entry and no unread dot, and nothing is recorded about
who has seen what.

One caveat if you maintain your own compose file rather than using the one in
this repo: Compose only passes a variable to the container if the service's
`environment:` block names it. The bundled compose forwards both of these; a
hand-written one that does not will make the setting look applied while changing
nothing. To confirm which state you are actually in, look at Settings >
Information: the paragraph about the install id is shown only when this install
is configured to send one. In the backend log, an install that sends one says so once
at startup ("This install reports an anonymous install id ..."), and one that does
not prints nothing. That line appears when the id first becomes readable, which is
normally within seconds of startup but is delayed if the database is not up yet.

## Common Commands

```bash
# Start everything, including the fork's source builds
docker compose up -d --build

# Update the fork: back up persistent data, review changes, then rebuild
git pull
docker compose up -d --build

# View backend / frontend logs
docker compose logs -f livecontext
docker compose logs -f frontend

# Stop everything
docker compose down

# Stop and delete all data (fresh start)
docker compose down -v

# Check health status
docker compose ps
```

## Startup Order and Timing

The compose file uses `depends_on` with health checks to ensure correct startup:

```
postgres (healthy) ─┐
redis (healthy) ────┼──► livecontext (healthy, ~2 min) ──► frontend
minio (healthy) ────┘
                    └──► minio-init (creates bucket, exits)
```

1. **PostgreSQL** - ready in ~5s
2. **Redis** - ready in ~3s
3. **MinIO** - ready in ~10s, then `minio-init` creates the `workflow-files` bucket
4. **Backend** - starts after all 3 are healthy. Flyway migrations run (~30s on first boot), then tool registration (~10s). Health check: `start_period: 120s`
5. **Frontend** - starts after backend is healthy. Ready in ~5s

Total first boot: **~2-3 minutes**. Subsequent starts: **~30-60 seconds**.

## Troubleshooting

### Cloud syncs fail with PKIX / certificate errors (TLS-intercepting proxy)

If the Bundles tab shows `PKIX path building failed` or `unable to find valid
certification path`, your network intercepts outbound HTTPS (corporate proxy or
antivirus) and re-signs certificates with a private root CA the containers do
not trust. Fix it at runtime, no rebuild needed:

1. Export your interception root-CA chain as a PEM file (ask IT, or export it
   from your OS certificate store).
2. Put it in a folder next to the compose file, e.g. `extra-ca/corp-root.pem`.
3. Mount the folder on the `livecontext` service and (for the bridge) point
   Node at the PEM:

```yaml
services:
  livecontext:
    volumes:
      - ./extra-ca:/app/extra-ca:ro   # entrypoint imports every .pem/.crt at startup
  bridge:
    volumes:
      - ./extra-ca:/app/extra-ca:ro
    environment:
      NODE_EXTRA_CA_CERTS: /app/extra-ca/corp-root.pem
```

4. `docker compose up -d livecontext bridge`. The app logs
   `[CE-TLS] Imported extra CA ...` on boot and cloud syncs work again.

### Backend fails to start - Flyway errors

If you see `relation "..." already exists`, the DB volume has stale data from a previous run with a different migration state.

```bash
# Nuclear option: wipe everything and start fresh
docker compose down -v
docker compose up -d
```

### Backend fails - "Could not deserialize" tool registration error

If you see `Could not deserialize string to java type: java.util.List<java.lang.String>`, a migration left bad JSONB data in `node_type_documentation`. This is fixed by migration V30. If it persists, wipe volumes (`down -v`) and rebuild.

### Frontend loads but SSR pages hang (Windows only)

If `http://localhost:3000` shows a blank page or times out on Windows with Docker Desktop (WSL2 backend), the prebuilt image already ships with Next.js compression disabled (it conflicts with the WSL2 port proxy), so this should not happen. If it does, restart the frontend:
```bash
docker compose restart frontend
```

### Port conflicts

If ports 3000 or 8080 are already in use, change `FRONTEND_PORT` freely:

```bash
# Move the app to another port (safe with the prebuilt image)
FRONTEND_PORT=9870 \
  docker compose up -d
```

Then open `http://localhost:9870`. Changing `BACKEND_PORT` is also safe with the prebuilt
image: the compose passes it to the `frontend` service, which serves it at runtime, and the
browser derives the backend origin from the address you opened the app with. Set both and
they stay consistent, no rebuild:

```bash
FRONTEND_PORT=9870 BACKEND_PORT=18080 docker compose up -d
```

### Backend out of memory

The backend has a 1.5 GB memory limit. If you see OOM errors:

```bash
# In docker-compose.yml, increase the livecontext memory limit:
# deploy.resources.limits.memory: 2048M
```

### Check container health

```bash
# Quick status
docker compose ps

# Backend health endpoint
curl http://localhost:8080/actuator/health

# Backend registered tools (should be 16)
curl http://localhost:8080/api/agent-tools | python -m json.tool | head -5
```

## Resource Usage

| Container | Memory Limit | Typical Usage |
|-----------|-------------|---------------|
| PostgreSQL | 256 MB | ~50 MB idle |
| Redis | 96 MB | ~10 MB idle |
| MinIO | 256 MB | ~30 MB idle |
| Backend | 1536 MB | ~800 MB after startup |
| Frontend | 256 MB | ~100 MB after startup |
| **Total** | **~2.4 GB** | **~1 GB idle** |
