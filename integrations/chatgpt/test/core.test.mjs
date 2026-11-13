import { test } from "node:test";
import assert from "node:assert/strict";
import {
  stableId,
  queueChange,
  signature,
  validateMutation,
} from "../dist/protocol.js";
test("stable identity follows Kotlin UTF16 length and UTF8 digest", () => {
  assert.equal(
    stableId("podcast", "https://example.test/rss"),
    "bbcd414c2c71946373dd3d9cc3152bee6bb57f1f191a1109f8ca88351817a798",
  );
});
test("queue changes preserve unrelated order and reject stale permutations", () => {
  assert.deepEqual(queueChange(["a", "b", "c"], "move", ["c", "a"]), [
    "c",
    "a",
    "b",
  ]);
  assert.deepEqual(queueChange(["a", "b"], "add", ["a", "c"]), ["a", "b", "c"]);
  assert.throws(() => queueChange(["a", "b"], "reorder", ["b"]), /permutation/);
  assert.throws(
    () =>
      queueChange(
        [],
        "add",
        Array.from({ length: 201 }, (_, i) => "e" + i),
      ),
    /200/,
  );
});
test("queue is an empty payload record when cleared, not a tombstone", () => {
  assert.deepEqual(queueChange(["a"], "clear", []), []);
  assert.throws(
    () => validateMutation("queue_library", { items: "a,a" }, 2),
    /distinct/,
  );
  assert.throws(
    () => validateMutation("queue_library", { items: "" }, 1),
    /protocol/,
  );
});
test("signatures sort payload fields but retain wire field ordering", () => {
  assert.equal(
    signature({
      key: "queue_library",
      baseVersion: 0,
      payload: { items: "" },
      generation: "g",
    }),
    '{"key":"queue_library","baseVersion":0,"payload":{"items":""},"deleted":false,"generation":"g"}',
  );
});
