/**
 * CSS for the public /models page, injected through `LandingShell`'s
 * `extraStyles` slot (same idiom as `docsStyles` and `changelogStyles`).
 *
 * Everything is scoped under `.landing-root` so it resolves against the public
 * site's own theme variables, which are decoupled from the app theme.
 */
export const modelsStyles = `
  /* Column geometry, shared by the header and the rows so they can never drift.
     Display is set per class, NOT here: a scoped '.landing-root .models-row'
     out-specifies Tailwind's '.hidden', so a header carrying 'hidden md:grid'
     would stay visible on mobile. The header owns its own display rule instead. */
  .landing-root .models-row,
  .landing-root .models-head {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
    gap: 0.75rem 1rem;
  }

  .landing-root .models-row {
    display: grid;
  }

  /* Below md the row stacks: the identity and the capability badges span the full
     width and the numeric cells pair up, each printing its own column label. */
  .landing-root .models-head {
    display: none;
  }

  .landing-root .models-row > :first-child,
  .landing-root .models-row > :last-child {
    grid-column: 1 / -1;
  }

  .landing-root .models-cell::before {
    content: attr(data-label);
    display: block;
    font-size: 0.6875rem;
    line-height: 1.2;
    color: var(--text-muted);
  }

  @media (min-width: 768px) {
    .landing-root .models-row,
    .landing-root .models-head {
      grid-template-columns: minmax(0, 1fr) 104px 76px 84px 90px 170px;
      align-items: center;
      gap: 1rem;
    }

    .landing-root .models-head {
      display: grid;
    }

    .landing-root .models-row > :first-child,
    .landing-root .models-row > :last-child {
      grid-column: auto;
    }

    .landing-root .models-cell::before {
      content: none;
    }
  }

  /* The chronological strip scrolls sideways and opens pinned to the present, so
     the fade marks the left edge (where the history continues) and leaves the
     newest column crisp. A mask, not an overlay: it works on any section color. */
  .landing-root .models-timeline-scroller {
    -webkit-mask-image: linear-gradient(to right, rgba(0, 0, 0, 0) 0, #000 72px, #000 100%);
    mask-image: linear-gradient(to right, rgba(0, 0, 0, 0) 0, #000 72px, #000 100%);
    scrollbar-width: thin;
    scrollbar-color: var(--landing-dash-track) transparent;
  }

  .landing-root .models-timeline-scroller::-webkit-scrollbar {
    height: 6px;
  }

  .landing-root .models-timeline-scroller::-webkit-scrollbar-thumb {
    background: var(--landing-dash-track);
    border-radius: 999px;
  }

  /* The search field paints its border inline, so a Tailwind \`focus:border-*\`
     utility could never win against it. Ring the field on keyboard focus here,
     where the selector is scoped and the inline style is not in the way. */
  .landing-root .models-search:focus-visible {
    outline: 2px solid var(--text-secondary);
    outline-offset: 1px;
  }

  .landing-root .models-chip:hover {
    background: var(--bg-tertiary);
    border-color: var(--text-muted);
  }
`;
