import { rustClientVersionRequirement } from '../shared.ts';

/**
 * Goldens hold this in place of the Rust client's major version, which comes from the crate's Cargo.toml
 * at build time, so a major release does not change them. rustClientVersion.test.ts checks the real value.
 */
export const RUST_CLIENT_MAJOR_PLACEHOLDER = '<rust-client-major>';

export function withRustClientMajorPlaceholder(code: string): string {
  return code.replace(
    `// Cargo.toml: mockserver-client = "${rustClientVersionRequirement()}"`,
    `// Cargo.toml: mockserver-client = "${RUST_CLIENT_MAJOR_PLACEHOLDER}"`,
  );
}
