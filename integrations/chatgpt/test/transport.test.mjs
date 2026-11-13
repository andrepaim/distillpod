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

test("user-token writes avoid server transactions and use atomic document preconditions", async () => {
  const { encode } = await import("../dist/firestore.js");
  let writes;
  const transport = async (url, options) => {
    if (url.endsWith("documents:beginTransaction"))
      return Response.json(
        { error: { status: "PERMISSION_DENIED" } },
        { status: 403 },
      );
    if (url.endsWith("documents:commit")) {
      writes = JSON.parse(options.body).writes;
      return Response.json({ writeResults: [{}] });
    }
    if (url.endsWith("/users/alice"))
      return Response.json({
        updateTime: "2026-01-01T00:00:00Z",
        fields: encode({
          active: true,
          generation: "g1",
          protocol: 2,
          sequence: 0,
        }).mapValue.fields,
      });
    return new Response("", { status: 404 });
  };
  const c = new Cloud(
    {
      projectId: "demo-test",
      apiKey: "demo-key",
      emulator: true,
      firestoreHost: "127.0.0.1:8085",
    },
    "alice",
    async () => "",
    transport,
  );
  await c.commit({
    id: "op1",
    key: "queue_library",
    generation: "g1",
    baseVersion: 0,
    payload: { items: "" },
  });
  assert.deepEqual(writes[0].currentDocument, {
    updateTime: "2026-01-01T00:00:00Z",
  });
  assert.deepEqual(
    writes.slice(1).map((w) => w.currentDocument),
    [{ exists: false }, { exists: false }, { exists: false }],
  );
});
