#!/usr/bin/env node
// Cross-language envelope smoke test, TypeScript/JavaScript side (RISKS.md R7 made
// executable). Emits an OperationResult wire document the way a future TS worker
// would — plain JSON.stringify, no Java anywhere. The committed output
// (spec/operation-results/valid/typescript-emitted-success.json) must schema-validate
// and round-trip byte-identically through the Java reader/writer.
//
// Regenerate: node spec/tools/emit-operation-result.mjs > \
//     spec/operation-results/valid/typescript-emitted-success.json

const result = {
  status: "success",
  output: {
    summary: "two files changed, one suggestion",
    score: 0.87,
    files: ["Foo.ts", "Bar.ts"],
  },
  usage: {
    tokens: 5310,
    costUsd: 0.031,
  },
};

process.stdout.write(JSON.stringify(result, null, 2) + "\n");
