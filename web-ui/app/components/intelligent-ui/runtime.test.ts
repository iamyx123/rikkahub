import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { sourceKey } from "./runtime";

test("portable state keys match Android SHA-256 on HTTP and Unicode sources", () => {
  for (const source of [
    "",
    "abc",
    "中文输入😀",
    "a".repeat(64),
    'root = Col([Text("卡片")])',
    "x".repeat(65536),
  ]) {
    assert.equal(sourceKey(source), createHash("sha256").update(source, "utf8").digest("hex"));
  }
});
