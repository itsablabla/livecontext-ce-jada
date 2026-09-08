import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator';

/**
 * Whether the PLATFORM can actually run this call on its own key.
 *
 * <p>Three facts, and all three are required. A platform credential row must exist for the
 * integration, it must be enabled and hold a secret ({@code available}), and a positive rate must
 * be published for what is being called ({@code hasPricing}). Dropping the last one would offer a
 * rate-free ride on the platform's key; dropping either of the first two offers a key that is not
 * there.
 *
 * <p><b>Why this is a shared function rather than an expression at each site.</b> Two surfaces ask
 * the same question of the same endpoint: the credential control, which decides whether to OFFER
 * the platform, and the studio, which decides whether to DEFAULT to it. Written twice they drift,
 * and the two ways they can drift are both bad: one offers what the other refuses, or the studio
 * bills a reader on a key the control would never have shown them.
 */
export function platformSellsThis(info: PlatformCredentialPublicInfo | null | undefined): boolean {
  return !!info?.available && info.platformCredentialId != null && !!info.hasPricing;
}
