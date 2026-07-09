import { defineConfig } from "tsup";

export default defineConfig({
  entry: ["src/index.ts"],
  format: ["esm"],
  dts: true,
  sourcemap: true,
  clean: true,
  treeshake: true,
  // the vendored JSON Schema is imported via a JSON module; bundle it in
  loader: { ".json": "json" },
});
