import { createHash } from "node:crypto";
export class Failure extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export function requireThat(
  condition: unknown,
  code: string,
  message: string,
): asserts condition {
  if (!condition) throw new Failure(code, message);
}
export function stableId(feed: string, guid: string) {
  return createHash("sha256")
    .update(`${feed.length}:${feed}${guid}`)
    .digest("hex");
}
export interface Mutation {
  key: string;
  baseVersion: number;
  payload: Record<string, string>;
  generation: string;
  id?: string;
}
export function signature(m: Mutation) {
  return JSON.stringify({
    key: m.key,
    baseVersion: m.baseVersion,
    payload: Object.fromEntries(
      Object.entries(m.payload).sort(([a], [b]) =>
        a < b ? -1 : a > b ? 1 : 0,
      ),
    ),
    deleted: false,
    generation: m.generation,
  });
}
export function queueChange(old: string[], action: string, args: string[]) {
  let next = [...old];
  if (action === "clear") next = [];
  else if (action === "add") next = [...new Set([...old, ...args])];
  else if (action === "reorder") {
    requireThat(
      args.length === old.length &&
        new Set(args).size === old.length &&
        args.every((x) => old.includes(x)),
      "invalid_queue",
      "Reorder must be an exact permutation",
    );
    next = args;
  } else if (action === "move") {
    const [id, before] = args;
    requireThat(
      args.length === 2 &&
        id !== before &&
        old.includes(id) &&
        old.includes(before),
      "invalid_queue",
      "Move requires two distinct existing episode IDs",
    );
    next = old.filter((x) => x !== id);
    next.splice(next.indexOf(before), 0, id);
  } else throw new Failure("usage", "Unknown queue action");
  requireThat(
    next.length <= 200,
    "invalid_queue",
    "Queue limit is 200 entries",
  );
  requireThat(
    next.every((x) => /^[A-Za-z0-9_-]{1,100}$/.test(x)),
    "invalid_queue",
    "Invalid episode ID",
  );
  return next;
}
export function publicLocator(raw: string) {
  const u = new URL(raw);
  requireThat(
    ["http:", "https:"].includes(u.protocol) &&
      !u.username &&
      !u.password &&
      !u.search &&
      !u.hash,
    "invalid_feed",
    "Only public HTTP(S) locators without credentials, query or fragment are supported",
  );
  return u.href;
}
export const identityFields = [
  "episodeId",
  "podcastId",
  "feedUrl",
  "originalFeedUrl",
  "podcastTitle",
  "podcastAuthor",
  "guid",
  "title",
  "audioUrl",
  "durationMs",
  "fingerprint",
];
export function validateMutation(
  key: string,
  p: Record<string, string>,
  protocol: number,
) {
  requireThat(
    [1, 2, 3].includes(protocol),
    "unsupported_protocol",
    "Unsupported account protocol",
  );
  requireThat(
    Object.values(p).every((x) => typeof x === "string") &&
      Buffer.byteLength(JSON.stringify(p)) <= 128 * 1024,
    "invalid_data",
    "Invalid payload",
  );
  if (key === "queue_library") {
    requireThat(
      protocol >= 2,
      "upgrade_required",
      "Enable queue sync in Android; protocol >= 2 required",
    );
    requireThat(
      Object.keys(p).join(",") === "items",
      "invalid_queue",
      "Unexpected queue fields",
    );
    const ids = p.items ? p.items.split(",") : [];
    requireThat(
      new Set(ids).size === ids.length,
      "invalid_queue",
      "Queue IDs must be distinct",
    );
    queueChange([], "add", ids);
    return;
  }
  if (key.startsWith("subscription_")) {
    requireThat(
      Object.keys(p).sort().join(",") ===
        [
          "subscribed",
          "podcastId",
          "feedUrl",
          "originalFeedUrl",
          "title",
          "author",
        ]
          .sort()
          .join(",") && p.subscribed === "true",
      "invalid_data",
      "Invalid subscription",
    );
  } else if (key.startsWith("episode_")) {
    requireThat(
      protocol >= 2,
      "upgrade_required",
      "Enable queue sync in Android",
    );
    requireThat(
      Object.keys(p).sort().join(",") === identityFields.sort().join(",") &&
        key === `episode_${p.episodeId}`,
      "invalid_data",
      "Incomplete episode identity",
    );
    requireThat(
      /^\d+$/.test(p.durationMs) &&
        Number(p.durationMs) < 9e15 &&
        p.guid.length > 0 &&
        Buffer.byteLength(p.guid) <= 2048,
      "invalid_data",
      "Invalid episode metadata",
    );
  } else
    throw new Failure(
      "unsupported_write",
      "This record type cannot be changed by the CLI",
    );
  for (const f of ["feedUrl", "originalFeedUrl", "audioUrl"])
    if (p[f]) {
      requireThat(
        Buffer.byteLength(p[f]) <= 2048,
        "invalid_data",
        "Locator too long",
      );
      publicLocator(p[f]);
    }
  for (const f of [
    "title",
    "author",
    "podcastTitle",
    "podcastAuthor",
    "fingerprint",
  ])
    if (p[f])
      requireThat(
        Buffer.byteLength(p[f]) <= 1000,
        "invalid_data",
        "Metadata exceeds protocol bound",
      );
  requireThat(
    /^[A-Za-z0-9_-]{1,100}$/.test(p.podcastId),
    "invalid_data",
    "Invalid podcast ID",
  );
}
