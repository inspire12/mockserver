import { ChildProcess } from 'child_process';

/** Platform detection result: os name, arch, and archive extension. */
export interface PlatformInfo {
  osName: 'linux' | 'darwin' | 'windows';
  arch: 'x86_64' | 'aarch64';
  ext: 'tar.gz' | 'zip';
}

/** Bundle base name and archive extension. */
export interface BundleMeta {
  name: string;
  ext: string;
}

/** Options for ensureBinary and runBinary. */
export interface BinaryOptions {
  /** Logging callback (default: no-op). */
  log?: (message: string) => void;
  /**
   * Additional spawn options for runBinary, which runs the bundled java
   * directly. `shell` is always false; on Windows `argv0` and
   * `windowsVerbatimArguments` are set by runBinary.
   */
  spawnOptions?: object;
}

/** Map Node's platform/arch to the bundle's {os}-{arch} naming. */
export function resolvePlatform(): PlatformInfo;

/** Compute the bundle base name (without extension) for a version. */
export function bundleBaseName(version: string): BundleMeta;

/** Resolve the cache base directory. */
export function cacheDir(): string;

/** Build the download URL for an asset file within a version's release. */
export function assetUrl(version: string, file: string): string;

/**
 * Ensure the platform bundle is present, downloading + verifying + extracting
 * on first use. Returns the path to the launcher binary.
 */
export function ensureBinary(version: string, opts?: BinaryOptions): Promise<string>;

/**
 * Download (if needed) and spawn the binary with the given args.
 * Returns a Promise that resolves to the child process.
 */
export function runBinary(version: string, args?: string[], opts?: BinaryOptions): Promise<ChildProcess>;

/**
 * Remove old version directories from the cache, keeping the current version
 * and at most maxPrevious older versions.
 */
export function pruneOldVersions(base: string, currentVer: string, maxPrevious?: number): void;
