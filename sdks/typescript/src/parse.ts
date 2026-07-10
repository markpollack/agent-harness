/**
 * Strict JSON parse with duplicate-key rejection (D-TS-4).
 *
 * `JSON.parse` silently keeps last-wins for duplicate object keys, and a reviver
 * cannot see the collision (it runs over the already-collapsed object) — verified.
 * Without detection, the corpus's duplicate-key fixtures would be wrongly ACCEPTED
 * (a conformance failure), so the SDK MUST detect duplicates at its own parse step
 * (freeze-notes; the TS analogue of Python's `object_pairs_hook`).
 *
 * Strategy: `JSON.parse` produces the value (guaranteed-correct number/string
 * handling); a separate lightweight scan over the raw text detects duplicate keys.
 * The scan decodes string escapes so `"a"` and `"a"` are recognized as the same
 * key (JSON semantics — `JSON.parse` would collapse them). The scanner's correctness
 * surface is only string tokenization + object nesting, not value construction.
 */

/** Thrown by {@link parseStrict}; `path` locates the offending duplicate key. */
export class DuplicateKeyError extends Error {
  readonly key: string;

  constructor(key: string) {
    super(`duplicate object key: ${JSON.stringify(key)}`);
    this.name = "DuplicateKeyError";
    this.key = key;
  }
}

const HEX = /[0-9a-fA-F]/;

/** Reads a JSON string starting at `text[i]` (the opening quote); returns decoded value + next index. */
function readString(text: string, start: number): { value: string; next: number } {
  let i = start + 1; // skip opening quote
  let out = "";
  while (i < text.length) {
    const ch = text[i];
    if (ch === '"') {
      return { value: out, next: i + 1 };
    }
    if (ch === "\\") {
      const esc = text[i + 1];
      switch (esc) {
        case '"':
          out += '"';
          break;
        case "\\":
          out += "\\";
          break;
        case "/":
          out += "/";
          break;
        case "b":
          out += "\b";
          break;
        case "f":
          out += "\f";
          break;
        case "n":
          out += "\n";
          break;
        case "r":
          out += "\r";
          break;
        case "t":
          out += "\t";
          break;
        case "u": {
          const hex = text.slice(i + 2, i + 6);
          if (hex.length !== 4 || ![...hex].every((c) => HEX.test(c))) {
            throw new SyntaxError(`invalid \\u escape at position ${i}`);
          }
          out += String.fromCharCode(parseInt(hex, 16));
          i += 4;
          break;
        }
        default:
          throw new SyntaxError(`invalid escape \\${esc ?? ""} at position ${i}`);
      }
      i += 2;
      continue;
    }
    out += ch;
    i += 1;
  }
  throw new SyntaxError("unterminated string");
}

interface Frame {
  readonly isObject: boolean;
  readonly keys?: Set<string>;
  expectKey: boolean;
}

/**
 * Scans raw JSON text for a duplicate key within any single object. Throws
 * {@link DuplicateKeyError} on the first one. Only tokenizes strings and tracks
 * object/array nesting — it does not build values.
 */
function assertNoDuplicateKeys(text: string): void {
  const stack: Frame[] = [];
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
      i += 1;
      continue;
    }
    if (ch === "{") {
      stack.push({ isObject: true, keys: new Set(), expectKey: true });
      i += 1;
      continue;
    }
    if (ch === "[") {
      stack.push({ isObject: false, expectKey: false });
      i += 1;
      continue;
    }
    if (ch === "}" || ch === "]") {
      stack.pop();
      i += 1;
      continue;
    }
    if (ch === ",") {
      const top = stack[stack.length - 1];
      if (top?.isObject) top.expectKey = true;
      i += 1;
      continue;
    }
    if (ch === ":") {
      i += 1;
      continue;
    }
    if (ch === '"') {
      const { value, next } = readString(text, i);
      const top = stack[stack.length - 1];
      if (top?.isObject && top.expectKey) {
        if (top.keys!.has(value)) {
          throw new DuplicateKeyError(value);
        }
        top.keys!.add(value);
        top.expectKey = false;
      }
      i = next;
      continue;
    }
    // number / true / false / null literal — skip to the next structural char
    i += 1;
  }
}

/** Parses JSON strictly: `JSON.parse` for the value, plus duplicate-key rejection. */
export function parseStrict(text: string): unknown {
  const value = JSON.parse(text); // throws SyntaxError on malformed JSON
  assertNoDuplicateKeys(text);
  return value;
}
