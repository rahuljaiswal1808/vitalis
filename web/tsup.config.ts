import { defineConfig } from "tsup";

export default defineConfig([
  // Library builds: ESM + CJS + types, tree-shakeable.
  {
    entry: { index: "src/index.ts", "core/index": "src/core/index.ts" },
    format: ["esm", "cjs"],
    dts: true,
    sourcemap: true,
    clean: true,
    treeshake: true,
  },
  // Standalone <script> build: attaches `window.Vitalis`. Drop into any web page.
  {
    entry: { "vitalis.global": "src/index.ts" },
    format: ["iife"],
    globalName: "Vitalis",
    sourcemap: true,
    minify: true,
    // Bundle MediaPipe into the standalone build so a plain <script> tag works with no
    // module loader. (npm/ESM/CJS consumers keep it external — see the builds above.)
    noExternal: [/@mediapipe\/tasks-vision/],
    outExtension: () => ({ js: ".js" }),
  },
]);
