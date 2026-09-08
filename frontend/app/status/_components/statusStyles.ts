/**
 * Styles for the public /status page, appended to the landing chrome the same way
 * changelogStyles is. Everything is expressed with the chrome's own tokens
 * (`--bg-*`, `--text-*`, `--border-color`) so the page follows the public site's
 * light/dark toggle without a second palette.
 *
 * The three status colours are defined here rather than reused from the app
 * theme: a status page's green/amber/red must stay legible on both public
 * backgrounds and must NOT drift when the product palette is retuned.
 */
export const statusStyles = `
  .landing-root {
    --st-green: #16a34a;
    --st-amber: #d97706;
    --st-red: #dc2626;
    --st-grey: #9ca3af;
    --st-track: rgba(120, 120, 120, 0.16);
  }
  .landing-root.dark {
    --st-green: #22c55e;
    --st-amber: #f59e0b;
    --st-red: #ef4444;
    --st-grey: #6b7280;
    --st-track: rgba(200, 200, 200, 0.14);
  }

  /* Headline card */
  .landing-root .st-hero {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 14px;
    padding: 20px 22px;
    border: 1px solid var(--border-color);
    border-radius: 14px;
    background: var(--bg-secondary);
  }
  .landing-root .st-hero-dot {
    width: 12px;
    height: 12px;
    border-radius: 999px;
    flex-shrink: 0;
  }
  .landing-root .st-hero-text { flex: 1 1 260px; min-width: 0; }
  .landing-root .st-hero-title {
    font-size: 1.125rem;
    font-weight: 500;
    color: var(--text-primary);
    line-height: 1.4;
  }
  .landing-root .st-hero-sub {
    margin-top: 4px;
    font-size: 0.875rem;
    color: var(--text-muted);
  }
  .landing-root .st-hero-link {
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--expression-color);
    white-space: nowrap;
  }

  /* Section heading */
  .landing-root .st-section {
    margin-top: 44px;
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }
  .landing-root .st-section h2 {
    font-size: 0.8125rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-muted);
    font-weight: 500;
  }
  .landing-root .st-section-note { font-size: 0.8125rem; color: var(--text-muted); }

  /* Incident cards */
  .landing-root .st-incident {
    margin-top: 12px;
    padding: 16px 18px;
    border: 1px solid var(--border-color);
    border-left-width: 3px;
    border-radius: 12px;
    background: var(--bg-secondary);
  }
  .landing-root .st-incident.is-incident { border-left-color: var(--st-red); }
  .landing-root .st-incident.is-maintenance { border-left-color: var(--st-amber); }
  .landing-root .st-incident.is-scheduled { border-left-color: var(--st-grey); }
  .landing-root .st-incident-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }
  .landing-root .st-badge {
    font-size: 0.6875rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    padding: 2px 7px;
    border-radius: 999px;
    border: 1px solid var(--border-color);
    color: var(--text-secondary);
    background: var(--bg-tertiary);
    white-space: nowrap;
  }
  .landing-root .st-incident-name {
    font-size: 0.9375rem;
    font-weight: 500;
    color: var(--text-primary);
  }
  .landing-root .st-incident-msg {
    margin-top: 8px;
    font-size: 0.875rem;
    line-height: 1.6;
    color: var(--text-secondary);
  }
  .landing-root .st-incident-meta {
    margin-top: 10px;
    font-size: 0.8125rem;
    color: var(--text-muted);
  }

  /* Component rows */
  .landing-root .st-components {
    margin-top: 12px;
    border: 1px solid var(--border-color);
    border-radius: 14px;
    overflow: hidden;
    background: var(--bg-secondary);
  }
  .landing-root .st-row { padding: 16px 18px; }
  .landing-root .st-row + .st-row { border-top: 1px solid var(--border-color); }
  .landing-root .st-row-head {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .landing-root .st-dot {
    width: 8px;
    height: 8px;
    border-radius: 999px;
    flex-shrink: 0;
  }
  .landing-root .st-row-name {
    font-size: 0.9375rem;
    color: var(--text-primary);
    flex: 1 1 auto;
    min-width: 0;
  }
  .landing-root .st-row-uptime {
    font-size: 0.8125rem;
    color: var(--text-muted);
    font-variant-numeric: tabular-nums;
  }

  /* 90-day bars */
  .landing-root .st-bars {
    margin-top: 12px;
    display: flex;
    align-items: stretch;
    gap: 2px;
    height: 30px;
  }
  .landing-root .st-bar {
    flex: 1 1 0;
    min-width: 0;
    border-radius: 2px;
    background: var(--st-track);
  }
  .landing-root .st-bar.is-up { background: var(--st-green); }
  .landing-root .st-bar.is-part { background: var(--st-amber); }
  .landing-root .st-bar.is-down { background: var(--st-red); }
  .landing-root .st-bar-scale {
    margin-top: 6px;
    display: flex;
    justify-content: space-between;
    font-size: 0.75rem;
    color: var(--text-muted);
  }
  /* A 90-bar strip is unreadable under ~640px, so the older bars drop out and
     the labels switch with them. How many remain is MOBILE_WINDOW_DAYS in
     lib/status/presentation.ts, which also computes both axis labels: this
     breakpoint and that constant are one decision expressed in two files. */
  .landing-root .st-bars-window-short { display: none; }
  @media (max-width: 640px) {
    .landing-root .st-bar.is-old { display: none; }
    .landing-root .st-bars-window-long { display: none; }
    .landing-root .st-bars-window-short { display: inline; }
  }

  .landing-root .st-fallback {
    margin-top: 12px;
    padding: 16px 18px;
    border: 1px dashed var(--border-color);
    border-radius: 12px;
    font-size: 0.875rem;
    color: var(--text-secondary);
    background: var(--bg-secondary);
  }
  .landing-root .st-foot {
    margin-top: 40px;
    font-size: 0.8125rem;
    line-height: 1.7;
    color: var(--text-muted);
  }
  .landing-root .st-foot a { color: var(--expression-color); font-weight: 500; }
`;
