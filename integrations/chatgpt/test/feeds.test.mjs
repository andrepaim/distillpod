import { test } from "node:test";
import assert from "node:assert/strict";
import { parseShow, allowedAddress } from "../dist/discovery.js";
test("rejects private and metadata network destinations", () => {
  for (const ip of [
    "127.0.0.1",
    "10.0.0.1",
    "169.254.169.254",
    "::1",
    "::ffff:127.0.0.1",
  ])
    assert.equal(allowedAddress(ip), false);
  assert.equal(allowedAddress("8.8.8.8"), true);
});
test("RSS identities use GUID and resolve enclosure relative to feed", () => {
  const r = parseShow(
    "https://example.test/rss",
    '<rss><channel><title>A</title><item><title>One</title><guid>g</guid><enclosure url="/one.mp3"/></item></channel></rss>',
  );
  assert.equal(r.episodes[0].audioUrl, "https://example.test/one.mp3");
  assert.equal(r.episodes[0].guid, "g");
});
test("rejects XML entities and signed source locators", () => {
  assert.throws(() =>
    parseShow("https://example.test/rss", "<!DOCTYPE rss><rss/>"),
  );
  assert.throws(() => parseShow("https://example.test/rss?key=x", "<rss/>"));
});
