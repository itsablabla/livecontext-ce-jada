import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['**/*.test.ts', '**/*.test.tsx'],
    exclude: ['**/node_modules/**', '**/dist/**'],
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    // Must stay ABOVE the `asyncUtilTimeout` configured in vitest.setup.ts (15s).
    // Vitest's default testTimeout is 5s, so a `waitFor`/`findBy*` that uses its full
    // budget was killed by the runner first: the test reported an opaque "Test timed
    // out in 5000ms" instead of the assertion that actually failed, and any test that
    // legitimately waits out a findBy* rejection could never pass. Keeping this margin
    // means the waitFor budget is the one that decides, and failures name their cause.
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '.'),
      // `server-only` throws on import by design, to fail a BUILD that pulls a
      // server module into a client bundle. That check still runs where it
      // matters (next build); here there is no client bundle to protect, and
      // leaving it live breaks any suite whose component tree reaches a server
      // module - which the shared public chrome now does, through the footer's
      // integration column. See vitest.stubs/server-only.ts.
      'server-only': path.resolve(__dirname, 'vitest.stubs/server-only.ts'),
    },
  },
});
