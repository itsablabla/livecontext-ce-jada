# LiveContext

## Jada fork: deploy the code in this repository

This fork adds per-provider **Endpoint URL** settings, configurable custom API
authentication, stricter MCP API-key scopes, and chat recovery when returning to
a conversation. Its backend and frontend must be built from source; the upstream
v0.2.15 images do not include these changes.

- Local Docker: `docker compose up -d --build`
- Coolify: use [`docker-compose.coolify.yml`](docker-compose.coolify.yml) and
  follow the [Coolify deployment guide](docker/COOLIFY.md).
- Existing installs: back up the database, MinIO data and signing/encryption keys
  before switching configurations. Never remove data volumes during an upgrade.

**The AI automation platform.** One message in, a working automation out.

Describe the job in chat and LiveContext builds it in front of you: a workflow you can read,
AI agents with scoped access and budgets you control, and a small app your team actually uses.
Chat, Workflow, Agent and App in one self-hosted platform. No code to write, nothing to stitch together.

**An open-source, self-hosted alternative to n8n, Zapier and Make, with AI agents built in.**

[![GitHub stars](https://img.shields.io/github/stars/livecontext-ai/livecontext-ce?style=flat&logo=github&color=e11d48)](https://github.com/livecontext-ai/livecontext-ce/stargazers)
[![Latest release](https://img.shields.io/github/v/release/livecontext-ai/livecontext-ce?color=16a34a)](https://github.com/livecontext-ai/livecontext-ce/releases/latest)
[![Discussions](https://img.shields.io/github/discussions/livecontext-ai/livecontext-ce?color=2496ED)](https://github.com/livecontext-ai/livecontext-ce/discussions)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-2496ED.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-e11d48.svg)
![Next.js](https://img.shields.io/badge/Next.js-16-000000.svg)
![Docker Compose](https://img.shields.io/badge/Docker%20Compose-ready-2496ED.svg)
![Self-hosted](https://img.shields.io/badge/self--hosted-%E2%9C%93-16a34a.svg)

<a href="https://livecontext.ai">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/landing/readme/hero-dark.webp" />
    <img src="frontend/public/landing/readme/hero-light.webp" alt="LiveContext builds an automation from a single chat message, then runs it: support, creator, sales, marketing and recruiting" width="100%" />
  </picture>
</a>

<sub>The builder, built by chat: one message in, a working automation out. Five real scenarios, one loop. <a href="frontend/public/landing/readme/hero-light.mp4">Watch it full size</a> &middot; <a href="https://livecontext.ai">Try the hosted version</a></sub>

<sub>⭐ If LiveContext looks useful, <a href="https://github.com/livecontext-ai/livecontext-ce">give it a star</a>. It helps other teams find it.</sub>

## Build it once. It runs as all four.

Most teams wire together a chatbot, an automation tool, an app builder and an agent framework.
LiveContext is all four on one canvas, every agent scoped, budgeted and audited, and you can see
exactly what each one did. The chat (shown above) builds it; here is what it runs as:

<table>
  <tr>
    <td width="50%" valign="top" align="center">
      <a href="frontend/public/landing/hero-stack/workflow-app.webp"><picture><source media="(prefers-color-scheme: dark)" srcset="frontend/public/landing/hero-stack/workflow-app-dark.webp" /><img src="frontend/public/landing/hero-stack/workflow-app.webp" alt="Workflow and the app it drives" width="100%" /></picture></a>
      <br/><b>Workflow + App</b><br/>
      The workflow and the app it drives, in one view. Draw the automation as a readable graph, then wrap it in a real interface: forms, dashboards and live approval screens your team or an agent can act on.
    </td>
    <td width="50%" valign="top" align="center">
      <a href="frontend/public/landing/hero-stack/agent.webp"><picture><source media="(prefers-color-scheme: dark)" srcset="frontend/public/landing/hero-stack/agent-dark.webp" /><img src="frontend/public/landing/hero-stack/agent.webp" alt="Agents" width="100%" /></picture></a>
      <br/><b>Agents</b><br/>
      A fleet of scoped agents, one per job: each with its own model, tools, files, credit budget and full audit trail. No black box.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" align="center">
      <a href="frontend/public/landing/hero-stack/table.webp"><picture><source media="(prefers-color-scheme: dark)" srcset="frontend/public/landing/hero-stack/table-dark.webp" /><img src="frontend/public/landing/hero-stack/table.webp" alt="Tables" width="100%" /></picture></a>
      <br/><b>Tables</b><br/>
      Built-in data tables your workflows and agents read, write and enrich. Filter, search and export, with no external database to wire up.
    </td>
    <td width="50%" valign="top" align="center">
      <a href="frontend/public/landing/hero-stack/data-metrics.webp"><picture><source media="(prefers-color-scheme: dark)" srcset="frontend/public/landing/hero-stack/data-metrics-dark.webp" /><img src="frontend/public/landing/hero-stack/data-metrics.webp" alt="Data &amp; metrics" width="100%" /></picture></a>
      <br/><b>Data &amp; metrics</b><br/>
      Every run charted: calls, tokens, success rate and duration, sliced per agent and per tool. Spot a regression and drill straight into it.
    </td>
  </tr>
</table>

> The workflow decides exactly what each agent sees and what it ships, so the same job runs at a
> fraction of the cost of a do-everything agent, every step is auditable, and your business never
> sits inside a black box.

This repository is the **Community Edition (CE)**: the full platform as a single self-hosted service
(see [LICENSE](LICENSE)). It is free to self-host and use in production inside your organization.

## Requirements

- Docker Engine 24+ with Compose v2 (or Docker Desktop 4.x and later)
- An AMD64 host for the bundled Chrome browser service
- 12 GB RAM recommended for source builds and the complete browser/rendering stack

## Quick start

Build this fork from its checked-out source (Docker must be installed and running):

```bash
git clone https://github.com/itsablabla/livecontext-ce-jada
cd livecontext-ce-jada
docker compose up -d --build
```

This builds Jada's backend and frontend, boots the stack, and serves on
**http://localhost:3000**. The public `npx livecontext` package installs upstream,
not this fork; do not use it to deploy or upgrade Jada.

Or run **Docker Compose** directly from a clone of this repo:

```bash
# From the repo root:
docker compose up -d --build

# Watch it come up. The "livecontext" service runs database migrations and registers
# its tools on first boot; wait until it reports "healthy" and "frontend" is up:
docker compose ps
```

Then open **http://localhost:3000** and create the first account (the first user becomes the admin).
The repository Docker Compose stack now includes interface screenshots/PDFs and the browser
agent/web-search sidecars by default; see [Browser and rendering features](#browser-and-rendering-features) below.

Configuration (LLM keys, SMTP, ports) is documented in [docker/README-CE.md](docker/README-CE.md).
Copy `docker/.env.ce.example` to set your own values, and never commit it.

### Running it on a server, NAS or VPS

For a direct Docker installation, `docker compose up -d --build` builds the backend,
frontend and browser service locally. Publish both ports (`3000` for the web UI, `8080` for the backend)
and open the app at that machine's address: `http://192.168.1.50:3000` talks to
`http://192.168.1.50:8080` on its own. If you put a reverse proxy in front and serve
everything on a single origin, set `GATEWAY_PUBLIC_URL` on the `frontend` service to the
browser-facing backend URL instead.

For Coolify, use [docker/COOLIFY.md](docker/COOLIFY.md) and
`docker-compose.coolify.yml`, which keeps host ports private and exposes one HTTPS
gateway. Upstream templates in `templates/` are not the Jada source-build deployment.

### Images

The backend and frontend are built from this repository. Unchanged bridge and
renderer services use version-pinned upstream images:

```
ghcr.io/livecontext-ai/livecontext-ce-bridge
ghcr.io/livecontext-ai/livecontext-ce-screenshot-renderer
```

The bundled browser Dockerfile installs AMD64 Google Chrome. Do not assume the
complete stack runs natively on ARM merely because some upstream images are multi-arch.

## Browser and rendering features

The repository Docker Compose stack now starts two heavier sidecars by default:

- **Interface screenshots and PDFs.** A headless Playwright/Chromium renderer
  (~1 GB image) lets interface nodes render PNG screenshots and PDFs.
- **Browser agent and web search.** A Chromium browser-use container plus a
  SearXNG metasearch sidecar (~2 GB) power `agent_browse` and `web_search`.

So a plain startup command is enough:

```bash
docker compose up -d
```

The first run takes longer because the bundled `websearch-service/` image is built
locally. See [docker/README-CE.md](docker/README-CE.md) for resource notes and
extra configuration such as `WEBSEARCH_CDP_JWT_SECRET`.

The `livecontext` npm CLI remains leaner: it does not bundle the browser-agent
build context, so these heavier sidecars are not available through `npx livecontext`.
Clone the repository and use `docker compose` directly when you want them.

## What's in the box

- **Workflow engine.** Visual builder and execution engine with parallel branches, loops, signals,
  human-approval steps, and triggers (schedule, webhook, chat, form, datasource).
- **AI agents.** Chat agents that design, build and run workflows, with per-workspace skills, scoped
  tool access, per-agent credit budgets and per-agent metrics.
- **Integration catalog.** 700+ ready-made integrations seeded at first boot, fully offline. Add your
  own as OpenAPI specs.
- **Interfaces and apps.** Small web pages served by your workflows (forms, dashboards, approval
  screens), shareable as standalone apps.
- **Tables.** Built-in data tables your workflows and agents can read and write.
- **One backend.** All backend services run as a single monolith JAR, with PostgreSQL, Redis, an
  S3-compatible object store and a lightweight tools bridge as its dependencies, plus the Next.js
  frontend. It all comes up with one `docker compose up`.

## Why self-host LiveContext

- **You stay in control.** Per-agent credit budgets, scoped access, a full audit trail and per-agent
  metrics. No black box.
- **Far fewer tokens.** The workflow constrains exactly what each agent sees and ships, so jobs cost a
  fraction of a do-everything agent.
- **Org-grade access.** Organizations and workspaces with role-based access control.
- **Yours to run.** The same platform on your own infrastructure.

## Managed version

Prefer not to run your own infrastructure? The managed service, with an always-current integration
catalog and hosted account management, lives at **[livecontext.ai](https://livecontext.ai)**. Those
hosted-only features are not part of the Community Edition.

## Building from source

CE runs from pulled images plus a locally built browser-agent websearch image by default. The full source is in this repo.
To build the images yourself instead of pulling, use the per-service Dockerfiles:
`backend/monolith-service/Dockerfile` (Java 21, the `ce` Maven profile), `frontend/Dockerfile`
(Node 20), and `mcp/bridge/Dockerfile`.

## Security

Please report vulnerabilities privately. See [SECURITY.md](SECURITY.md).

## License

LiveContext CE is licensed under the **GNU Affero General Public License v3.0
(AGPL-3.0)**, see [LICENSE](LICENSE). You are free to use, self-host, modify and
redistribute it, including commercially. One condition matters most: if you run a
modified version as a network service, the AGPL requires you to make the
corresponding source of your changes available to that service's users.

The **LiveContext** name and logo are trademarks of their owner and are not
covered by the AGPL, see [TRADEMARKS](TRADEMARKS). Third-party components ship
under their own licenses, see [NOTICE](NOTICE) and
[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).

---

⭐ **If LiveContext is useful to you, star the repo.** It is the simplest way to help other teams
discover it, and it means a lot to a small team. Questions or ideas? Open a
[Discussion](https://github.com/livecontext-ai/livecontext-ce/discussions).
