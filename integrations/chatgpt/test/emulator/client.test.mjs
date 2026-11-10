import { before, test } from "node:test";
import assert from "node:assert/strict";
import { Cloud, encode } from "../../dist/firestore.js";
const config = {
  projectId: "demo-distillpod-sync",
  apiKey: "demo-key",
  emulator: true,
  firestoreHost: process.env.FIRESTORE_EMULATOR_HOST,
  authHost: process.env.FIREBASE_AUTH_EMULATOR_HOST,
};
if (
  config.firestoreHost !== "127.0.0.1:8085" ||
  config.authHost !== "127.0.0.1:9099"
)
  throw Error("Isolated Auth/Firestore endpoints required");
let cloud, uid, token;
before(async () => {
  const r = await fetch(
    `http://${config.authHost}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-key`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ returnSecureToken: true }),
    },
  );
  const a = await r.json();
  uid = a.localId;
  token = a.idToken;
  cloud = new Cloud(config, uid, async () => token);
  await fetch(`${cloud.url}${cloud.root}`, {
    method: "PATCH",
    headers: {
      Authorization: "Bearer owner",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      fields: encode({
        generation: "g1",
        sequence: 0,
        active: true,
        protocol: 2,
      }).mapValue.fields,
    }),
  });
});
test("actual REST atomic commit obeys rules, creates receipt and retry is idempotent", async () => {
  const m = {
    id: "operation1",
    generation: "g1",
    key: "queue_library",
    baseVersion: 0,
    payload: { items: "" },
  };
  const r = await cloud.commit(m);
  assert.equal(r.version, 1);
  assert.deepEqual(await cloud.commit(m), r);
  assert.equal((await cloud.head()).sequence, 1);
});
test("stale queue edits and operation identity reuse fail", async () => {
  await assert.rejects(
    cloud.commit({
      id: "operation2",
      generation: "g1",
      key: "queue_library",
      baseVersion: 0,
      payload: { items: "a" },
    }),
    (e) => e.code === "conflict",
  );
  await assert.rejects(
    cloud.commit({
      id: "operation1",
      generation: "g1",
      key: "queue_library",
      baseVersion: 0,
      payload: { items: "a" },
    }),
    (e) => e.code === "operation_mismatch",
  );
});
test("another user cannot access the library even with a forged path", async () => {
  const impostor = new Cloud(config, "other-user", async () => token);
  await assert.rejects(impostor.head(), (e) => e.code === "permission_denied");
});
test("invalid companion bypass denied by actual rules", async () => {
  await assert.rejects(
    cloud.request(
      `${cloud.root}/entities/queue_library`,
      { fields: encode({ version: 99 }).mapValue.fields },
      "PATCH",
    ),
    (e) => e.code === "permission_denied",
  );
});
test("reads produce stable cloud scope and account changes fail", async () => {
  const s = await cloud.snapshot();
  assert.equal(s.complete, true);
  assert.equal(s.rows.length, 1);
  await assert.rejects(
    cloud.commit({
      id: "op3",
      generation: "other",
      key: "queue_library",
      baseVersion: 1,
      payload: { items: "" },
    }),
    (e) => e.code === "account_mismatch",
  );
});

test("CLI reads and prepared queue operations round trip through real rules", async () => {
  const { mkdtemp, writeFile, rm } = await import("node:fs/promises");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const { execFile } = await import("node:child_process");
  const { promisify } = await import("node:util");
  const dir = await mkdtemp(join(tmpdir(), "distillpod-cli-"));
  const profile = join(dir, "profile");
  const { State } = await import("../../dist/state.js");
  const state = new State(profile);
  await state.init();
  await state.write("auth.json", {
    projectId: config.projectId,
    uid,
    idToken: token,
    refreshToken: "demo-unused",
    expiresAt: Date.now() + 600000,
  });
  const file = join(dir, "config.json");
  await writeFile(file, JSON.stringify(config), { mode: 0o600 });
  const run = async (...args) =>
    JSON.parse(
      (
        await promisify(execFile)(process.execPath, [
          "dist/cli.js",
          "--config",
          file,
          "--private-state",
          profile,
          ...args,
        ])
      ).stdout,
    );
  try {
    const status = await run("library", "status");
    assert.equal(status.data.scope, "cloud_synced");
    const p = await run("queue", "clear");
    assert.equal(p.data.noOp, true);
    const identity = {
      episodeId: "episode-a",
      podcastId: "show",
      feedUrl: "https://example.test/rss",
      originalFeedUrl: "https://example.test/rss",
      podcastTitle: "Example",
      podcastAuthor: "Author",
      guid: "one",
      title: "One",
      audioUrl: "https://example.test/one.mp3",
      durationMs: "1234",
      fingerprint: "f",
    };
    await cloud.commit({
      id: "identity-a",
      key: "episode_episode-a",
      baseVersion: 0,
      generation: "g1",
      payload: identity,
    });
    const add = await run("queue", "add", "episode-a");
    assert.ok(add.data.operationId);
    await run("operations", "apply", add.data.operationId);
    assert.equal((await run("queue", "get")).data.items[0].id, "episode-a");
    const clear = await run("queue", "clear");
    await run("operations", "apply", clear.data.operationId);
    assert.deepEqual((await run("queue", "get")).data.items, []);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("concurrent subscription writers preserve unique root sequences", async () => {
  const payload = {
    subscribed: "true",
    podcastId: "show",
    feedUrl: "https://example.test/rss",
    originalFeedUrl: "https://example.test/rss",
    title: "Show",
    author: "Author",
  };
  const before = (await cloud.head()).sequence;
  await Promise.all(
    ["one", "two"].map((id) =>
      cloud.commit({
        id: `follow-${id}`,
        key: `subscription_${id}`,
        baseVersion: 0,
        generation: "g1",
        payload,
      }),
    ),
  );
  assert.equal((await cloud.head()).sequence, before + 2);
});
