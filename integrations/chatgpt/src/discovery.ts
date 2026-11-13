import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { lookup } from "node:dns/promises";
import { XMLParser } from "fast-xml-parser";
import ipaddr from "ipaddr.js";
import { publicLocator, requireThat, stableId } from "./protocol.js";
export function allowedAddress(ip: string) {
  try {
    return ipaddr.process(ip).range() === "unicast";
  } catch {
    return false;
  }
}
export async function publicGet(
  raw: string,
  remaining = 4,
): Promise<{ url: string; text: string }> {
  const u = new URL(raw);
  requireThat(
    ["http:", "https:"].includes(u.protocol) &&
      !u.username &&
      !u.password &&
      !u.hash,
    "invalid_feed",
    "Invalid public URL",
  );
  const addresses = await lookup(u.hostname.replace(/^\[|\]$/g, ""), {
    all: true,
  });
  requireThat(
    addresses.length && addresses.every((a) => allowedAddress(a.address)),
    "invalid_feed",
    "Private network destinations are unavailable",
  );
  return new Promise((resolve, reject) => {
    const req = (u.protocol === "https:" ? httpsRequest : httpRequest)(
      u,
      {
        signal: AbortSignal.timeout(20000),
        headers: {
          "User-Agent": "DistillPod-CLI/0.1",
          "Accept-Encoding": "identity",
        },
        lookup: ((_host: any, _opts: any, cb: any) =>
          _opts.all
            ? cb(null, addresses)
            : cb(null, addresses[0].address, addresses[0].family)) as any,
      },
      (res) => {
        if (
          res.statusCode &&
          res.statusCode >= 300 &&
          res.statusCode < 400 &&
          res.headers.location
        ) {
          res.resume();
          if (!remaining) return reject(Error("Redirect limit reached"));
          return publicGet(
            new URL(res.headers.location, u).href,
            remaining - 1,
          ).then(resolve, reject);
        }
        if (
          res.statusCode !== 200 ||
          (res.headers["content-encoding"] &&
            res.headers["content-encoding"] !== "identity")
        ) {
          res.resume();
          return reject(
            Error("Public source unavailable or compressed response refused"),
          );
        }
        let size = 0;
        const chunks: Buffer[] = [];
        res.on("data", (b: Buffer) => {
          size += b.length;
          if (size > 3_000_000) req.destroy(Error("Public response too large"));
          else chunks.push(b);
        });
        res.on("error", reject);
        res.on("end", () =>
          resolve({
            url: u.href,
            text: Buffer.concat(chunks).toString("utf8"),
          }),
        );
      },
    );
    req.setTimeout(15000, () => req.destroy(Error("Public request timeout")));
    req.on("error", reject);
    req.end();
  });
}
const list = (x: any): any[] =>
  x === undefined ? [] : Array.isArray(x) ? x : [x];
const text = (x: any): string =>
  String(typeof x === "object" ? (x?.["#text"] ?? "") : (x ?? ""))
    .replace(/<[^>]*>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
const bounded = (s: string) => {
  while (Buffer.byteLength(s) > 1000) s = s.slice(0, -1);
  return s;
};
export function parseShow(raw: string, xml: string, existingId?: string) {
  const url = publicLocator(raw);
  requireThat(
    !/<!\s*(DOCTYPE|ENTITY)/i.test(xml) && Buffer.byteLength(xml) <= 3_000_000,
    "invalid_feed",
    "Unsafe or oversized XML",
  );
  requireThat(
    !/youtube\.com|youtu\.be/i.test(new URL(url).hostname),
    "invalid_feed",
    "YouTube sources are local-only",
  );
  const doc = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@",
    removeNSPrefix: true,
    parseTagValue: false,
    parseAttributeValue: false,
  }).parse(xml);
  const c = doc.rss?.channel ?? doc.feed;
  requireThat(c, "invalid_feed", "RSS or Atom feed required");
  const podcastId = existingId ?? stableId("podcast", url);
  const title = bounded(text(c.title)),
    author = bounded(text(c.author?.name ?? c.author ?? c.managingEditor));
  const episodes = list(c.item ?? c.entry).flatMap((e: any) => {
    const enclosure =
      e.enclosure ?? list(e.link).find((l) => l["@rel"] === "enclosure");
    const src = enclosure?.["@url"] ?? enclosure?.["@href"];
    if (!src) return [];
    let audioUrl: string;
    try {
      audioUrl = publicLocator(new URL(src, url).href);
    } catch {
      return [];
    }
    const guid = text(e.guid ?? e.id) || audioUrl;
    const episodeId = stableId(podcastId, guid);
    const parts = text(e.duration).split(":");
    const duration = parts.reduce((n, s) => n * 60 + Number(s), 0);
    const publishedAt = Date.parse(text(e.pubDate ?? e.published ?? e.updated));
    return [
      {
        episodeId,
        podcastId,
        feedUrl: url,
        originalFeedUrl: url,
        podcastTitle: title,
        podcastAuthor: author,
        guid,
        title: bounded(text(e.title) || "Untitled"),
        audioUrl,
        durationMs: String(
          Number.isFinite(duration) && duration >= 0 && duration <= 604800
            ? Math.round(duration * 1000)
            : 0,
        ),
        fingerprint: stableId("enclosure", audioUrl),
        publishedAt: Number.isFinite(publishedAt) ? publishedAt : null,
      },
    ];
  });
  return {
    podcastId,
    feedUrl: url,
    originalFeedUrl: url,
    title,
    author,
    episodes: [
      ...new Map(episodes.map((e: any) => [e.episodeId, e])).values(),
    ] as any[],
  };
}
export async function inspectShow(url: string, id?: string) {
  publicLocator(url);
  const r = await publicGet(url);
  const show = parseShow(r.url, r.text, id);
  return {
    ...show,
    originalFeedUrl: url,
    episodes: show.episodes.map((e) => ({ ...e, originalFeedUrl: url })),
  };
}
export async function searchShows(query: string) {
  requireThat(
    query.length > 0 && query.length <= 300,
    "usage",
    "Search query must be 1–300 characters",
  );
  const r = await publicGet(
    "https://itunes.apple.com/search?" +
      new URLSearchParams({ term: query, media: "podcast", limit: "20" }),
  );
  return (JSON.parse(r.text).results ?? []).flatMap((x: any) => {
    try {
      return [
        {
          title: text(x.collectionName),
          author: text(x.artistName),
          feedUrl: publicLocator(x.feedUrl),
        },
      ];
    } catch {
      return [];
    }
  });
}
