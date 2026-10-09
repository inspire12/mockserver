// Gives the Node-run codegen emitter scripts the values Vite's `define` gives the dashboard build and
// tests (vite.config.ts, vitest.config.ts). Import it before any codegen module.
import { rustClientMajorVersion } from '../build-constants.ts';

globalThis.__RUST_CLIENT_MAJOR_VERSION__ = rustClientMajorVersion();
