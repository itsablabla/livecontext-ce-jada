/**
 * Test stub for the `server-only` package.
 *
 * <p>The real module throws on import so that a server-only file reaching a client
 * bundle fails loudly. That guard is enforced by the Next BUILD, which is where it
 * belongs and where it still runs; in vitest there is no client bundle to protect,
 * so importing the real one only breaks any test whose component tree happens to
 * reach a server module.
 *
 * <p>It became necessary when the shared public chrome (`LandingShell`) gained the
 * footer's integration column, which reads the catalog: every suite rendering a
 * public page suddenly needed its own `vi.mock('server-only')`, and the two that
 * already had one were the only ones that passed. Aliasing it once here keeps that
 * per-file stub from being a thing anyone has to remember.
 */
export {};
