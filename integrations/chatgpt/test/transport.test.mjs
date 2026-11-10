import { test } from "node:test";
import assert from "node:assert/strict";
import { Cloud } from "../dist/firestore.js";
test("a missing commit endpoint must never become success", async () => {
  const c = new Cloud(
    {
      projectId: "demo-test",
      apiKey: "demo-key",
      emulator: true,
      firestoreHost: "127.0.0.1:8085",
    },
    "alice",
    async () => "",
    async () => new Response("", { status: 404 }),
  );
  await assert.rejects(
    c.request("documents:commit", {}),
    (e) => e.code === "service",
  );
});
test("emulator hosts and production configuration cannot be mixed", () => {
  for (const config of [
    {
      projectId: "production",
      apiKey: "x",
      emulator: true,
      firestoreHost: "127.0.0.1:8085",
    },
    { projectId: "production", apiKey: "x", firestoreHost: "127.0.0.1:8085" },
    {
      projectId: "demo-test",
      apiKey: "x",
      emulator: true,
      firestoreHost: "example.com:8085",
    },
  ])
    assert.throws(() => new Cloud(config, "alice", async () => ""));
});
