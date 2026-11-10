#!/usr/bin/env node
import { parseArgs } from "node:util";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { State } from "./state.js";
import { Auth } from "./auth.js";
import { Cloud, type Config } from "./firestore.js";
import { Operations } from "./operations.js";
import { inspectShow, searchShows } from "./discovery.js";
import { Failure, requireThat, stableId, identityFields } from "./protocol.js";
const HELP = `DistillPod CLI (cloud library only)
Required: --config /private/client.json --private-state /private/state
Configuration: projectId, apiKey, clientId, optional clientSecret. Explicit demo mode: emulator=true, firestoreHost, authHost.
Commands:
 auth login | status | logout (demo: login --emulator-email person@example.test)
 doctor | library status
 subscriptions list | add <public-feed-url> --public-source
 episodes list | get <episode-id>
 moments search <words> | get <moment-id>
 preferences get
 shows search <words> | inspect <feed-url> | episodes <feed-url>
 queue get | add <episode-ids...> | move <id> <before-id> | reorder <ids...> | clear
 queue add-feed <feed-url> <guid...> --public-source
 operations apply <operation-id> | status <operation-id>
Mutations prepare a private proposal; apply it using operations apply. If the user already requested the change, the agent may prepare and apply without asking again.
--limit 1000 (maximum 5000), --json. Queue actions affect only shared entries; phone application is unverified, and current playback is not stopped.
`;
async function main() {
  const { values, positionals: p } = parseArgs({
    allowPositionals: true,
    options: {
      config: { type: "string" },
      "private-state": { type: "string" },
      json: { type: "boolean" },
      help: { type: "boolean" },
      limit: { type: "string" },
      "public-source": { type: "boolean" },
      "emulator-email": { type: "string" },
    },
  });
  if (values.help) {
    console.log(HELP);
    return;
  }
  requireThat(values.config && values["private-state"], "usage", HELP);
  const config = JSON.parse(await readFile(values.config, "utf8")) as Config;
  const directory = resolve(values["private-state"]);
  requireThat(
    !directory.startsWith(resolve(import.meta.dirname, "../../..") + "/"),
    "configuration",
    "Private state must be outside the integration checkout",
  );
  const state = new State(directory);
  await state.init();
  return state.lock(async () => {
    const auth = new Auth(config, state);
    const [family, action, ...args] = p;
    if (family === "auth") {
      if (action === "login")
        return values["emulator-email"]
          ? auth.emulatorLogin(values["emulator-email"])
          : auth.login();
      if (action === "logout") {
        const { readdir } = await import("node:fs/promises");
        for (const f of await readdir(directory))
          if (f === "auth.json" || /^operation-.*\.json$/.test(f))
            await state.remove(f);
        return { signedIn: false };
      }
      if (action === "status") {
        const a = await state.read("auth.json");
        return {
          signedIn: !!a,
          projectMatches: a?.projectId === config.projectId,
          needsRefresh: a?.expiresAt < Date.now(),
        };
      }
    }
    const a = await state.read("auth.json");
    requireThat(a, "auth_required", "Run auth login first");
    const cloud = new Cloud(config, a.uid, () => auth.token());
    const ops = new Operations(cloud, state);
    if (family === "operations") {
      requireThat(
        /^[a-f0-9-]{36}$/.test(args[0] ?? ""),
        "usage",
        "Operation ID required",
      );
      if (action === "apply") return ops.apply(args[0]);
      if (action === "status") {
        const op = await state.read(`operation-${args[0]}.json`);
        requireThat(
          op && op.uid === a.uid && op.projectId === config.projectId,
          "account_mismatch",
          "Operation unavailable",
        );
        return {
          operationId: op.id,
          completed: op.completed,
          total: op.mutations.length,
          phoneApplied: "unknown",
        };
      }
    }
    if (family === "shows") {
      if (action === "search") return searchShows(args.join(" "));
      if (["inspect", "episodes"].includes(action)) return inspectShow(args[0]);
    }
    if (family === "doctor")
      return {
        node: process.version,
        projectId: config.projectId,
        head: await cloud.head(),
        scope: "cloud_synced",
        phoneApplied: "unknown",
      };
    if (
      family === "queue" &&
      ["add", "move", "reorder", "clear"].includes(action)
    )
      return ops.queue(action, args);
    if (
      (family === "subscriptions" && action === "add") ||
      (family === "queue" && action === "add-feed")
    ) {
      requireThat(
        values["public-source"],
        "public_review_required",
        "Acknowledge this is a public feed using --public-source",
      );
      const snap = await cloud.snapshot(Number(values.limit ?? 1000));
      let show = await inspectShow(args[0]);
      const old = snap.rows.find(
        (r) =>
          r.key.startsWith("subscription_") &&
          [r.payload.feedUrl, r.payload.originalFeedUrl].some((u) =>
            [show.feedUrl, show.originalFeedUrl].includes(u),
          ),
      );
      if (old) show = await inspectShow(args[0], old.payload.podcastId);
      if (family === "subscriptions") {
        if (old) return { noOp: true };
        const { episodes, ...payload } = show;
        return ops.prepare([
          {
            key: `subscription_${stableId("subscription", show.originalFeedUrl)}`,
            baseVersion:
              (
                await cloud.get(
                  `${cloud.root}/entities/subscription_${stableId("subscription", show.originalFeedUrl)}`,
                )
              )?.version ?? 0,
            generation: snap.head.generation,
            payload: { ...payload, subscribed: "true" },
          },
        ]);
      }
      requireThat(
        snap.head.protocol >= 2,
        "upgrade_required",
        "Enable queue sync in Android",
      );
      const ids: string[] = [];
      const mutations: any[] = [];
      for (const guid of args.slice(1)) {
        const candidate = show.episodes.find((e) => e.guid === guid);
        requireThat(
          candidate,
          "missing_metadata",
          "Selected GUID not found in feed",
        );
        const existing = snap.rows.find(
          (r) =>
            r.payload.guid === guid && r.payload.podcastId === show.podcastId,
        );
        const episode = existing?.payload ?? candidate;
        ids.push(episode.episodeId);
        if (!snap.rows.some((r) => r.key === `episode_${episode.episodeId}`))
          mutations.push({
            key: `episode_${episode.episodeId}`,
            baseVersion: 0,
            generation: snap.head.generation,
            payload: Object.fromEntries(
              identityFields.map((k) => [k, episode[k]]),
            ),
          });
      }
      requireThat(ids.length, "usage", "Select at least one episode GUID");
      const q = snap.rows.find((r) => r.key === "queue_library");
      const { queueChange } = await import("./protocol.js");
      const items = queueChange(
        q?.payload.items ? q.payload.items.split(",") : [],
        "add",
        ids,
      ).join(",");
      if (items === (q?.payload.items ?? "")) return { noOp: true };
      return ops.prepare([
        ...new Map(mutations.map((m) => [m.key, m])).values(),
        {
          key: "queue_library",
          baseVersion: q?.version ?? 0,
          generation: snap.head.generation,
          payload: { items },
        },
      ]);
    }
    const snap = await cloud.snapshot(Number(values.limit ?? 1000));
    if (family === "library" && action === "status")
      return {
        observedAt: snap.observedAt,
        scope: snap.scope,
        complete: snap.complete,
        protocol: snap.head.protocol,
        records: snap.rows.length,
        phoneApplied: "unknown",
      };
    let rows = snap.rows;
    if (family === "subscriptions" && action === "list")
      rows = rows.filter((r) => r.key.startsWith("subscription_"));
    else if (family === "moments" && ["search", "get"].includes(action)) {
      rows = rows.filter((r) => r.key.startsWith("moment_"));
      rows =
        action === "get"
          ? rows.filter((r) => r.key === `moment_${args[0]}`)
          : rows.filter((r) =>
              [
                r.payload.excerpt,
                r.payload.quote,
                r.payload.insight,
                r.payload.title,
              ]
                .join(" ")
                .toLocaleLowerCase()
                .includes(args.join(" ").toLocaleLowerCase()),
            );
    } else if (family === "episodes" && ["list", "get"].includes(action)) {
      const map = new Map<string, any>();
      for (const r of rows)
        if (r.payload.episodeId) {
          const e = map.get(r.payload.episodeId) ?? {
            episodeId: r.payload.episodeId,
            metadata: Object.fromEntries(
              identityFields.map((k) => [k, r.payload[k]]),
            ),
          };
          if (r.key.startsWith("progress_"))
            e.positionMs = r.payload.positionMs;
          if (r.key.startsWith("completion_")) {
            e.completed = r.payload.completed;
            e.completionOrigin = r.payload.origin;
          }
          map.set(e.episodeId, e);
        }
      return {
        scope: snap.scope,
        observedAt: snap.observedAt,
        complete: true,
        episodes: [...map.values()].filter(
          (e) => action !== "get" || e.episodeId === args[0],
        ),
        warning:
          "Cloud records are not a complete episode catalogue or listening history.",
      };
    } else if (family === "preferences" && action === "get")
      rows = rows.filter((r) => r.key.startsWith("preference_"));
    else if (family === "queue" && action === "get") {
      const q = rows.find((r) => r.key === "queue_library");
      return {
        scope: snap.scope,
        version: q?.version ?? 0,
        items: (q?.payload.items ? q.payload.items.split(",") : []).map(
          (id) => ({
            id,
            metadata:
              rows.find((r) => r.payload.episodeId === id)?.payload ?? null,
          }),
        ),
        phoneApplied: "unknown",
        warning:
          "Private phone entries are excluded; cloud changes may conflict with active phone edits.",
      };
    } else throw new Failure("usage", HELP);
    return {
      scope: snap.scope,
      observedAt: snap.observedAt,
      complete: true,
      records: rows,
    };
  });
}
main()
  .then((data) => {
    if (data !== undefined)
      console.log(JSON.stringify({ schemaVersion: 1, ok: true, data }));
  })
  .catch((e) => {
    console.log(
      JSON.stringify({
        schemaVersion: 1,
        ok: false,
        error: {
          code: e instanceof Failure ? e.code : "unavailable",
          message:
            e instanceof Failure
              ? e.message
              : "Operation failed; no success is established. Retry the same operation if its outcome is unknown.",
        },
      }),
    );
    process.exitCode = 1;
  });
