import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The major version of the Rust client crate, which the dashboard's Rust code snippets name in their
 * Cargo.toml comment. Read from the crate's manifest at build time, so a release that bumps the crate
 * changes the snippets too. Throws (failing the build) when the manifest is missing or has no version.
 */
export function rustClientMajorVersion(): string {
  const manifestPath = fileURLToPath(new URL('../mockserver-client-rust/Cargo.toml', import.meta.url));
  let manifest: string;
  try {
    manifest = readFileSync(manifestPath, 'utf8');
  } catch (e) {
    throw new Error(`Cannot read ${manifestPath}, which gives the Rust client version the dashboard's Rust snippets name: ${String(e)}`);
  }
  const packageSection = manifest.split(/^\[package\]\s*$/m)[1]?.split(/^\[/m)[0] ?? '';
  const major = /^version\s*=\s*"(\d+)\.\d+\.\d+[^"]*"/m.exec(packageSection)?.[1];
  if (!major) {
    throw new Error(`No [package] version in ${manifestPath}; cannot set the Rust client version the dashboard's Rust snippets name`);
  }
  return major;
}
