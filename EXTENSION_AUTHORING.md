# Writing a Relay source extension

This document is a complete brief. Hand it to a coding agent (or read it yourself) and it should
be enough to build, verify, and publish a working source extension without further context.

Relay is an Android-first music player. A **source extension** is a separately installed APK that
Relay loads into its own process to browse and search a music service, returning normalised
records. Relay owns the library, playback, queue, downloads, and all UI; your extension only
answers questions about a catalogue.

---

## 1. Before writing any code: qualify the source

Most failed extensions fail here, not in the Kotlin. Work through this in order and stop at the
first "no".

1. **Is there a public endpoint that needs no API key or login?** Relay has no credential storage
   for sources yet. If it needs a key, stop.
2. **Does one request return a list of *tracks*?** Endpoints that return albums/items force one
   extra request per result to find playable files, which will not fit inside Relay's 10-second
   search budget. If the API is item-shaped, either find a track-shaped endpoint or pick another
   source.
3. **Is everything HTTPS?** Relay rejects `http://` for catalogues, streams, artwork, and
   downloads. No exceptions.
4. **Verify the media URL actually fetches — this is the step people skip.** Getting JSON back
   from the API proves nothing about the audio. Take a real file URL from the API response and
   fetch it from a plain client:

   ```sh
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -L "<file url from the API>"
   ```

   You want `200` and an `audio/*` content type. A `403` means hotlink protection: the host wants
   a `Referer`, a specific `User-Agent`, or a cookie. Retry with the headers you intend to declare
   in `getMediaRequestHeaders()`:

   ```sh
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -L \
     -A "<your user agent>" -H "Referer: <the page the file belongs to>" "<file url>"
   ```

   If you cannot make it return `200` with headers Relay is allowed to send (see §5), the source
   cannot stream and the extension is not worth building. Say so and stop.
5. **Is the licensing appropriate?** Prefer Creative Commons, public domain, netlabel, and
   artist-permissioned catalogues. Do not build a source that circumvents paid access.

Record the answers to 1–4 in your PR or commit message. A reviewer should not have to re-derive
them.

---

## 2. The trust model, and what it means for you

Relay verifies the repository catalogue signature and the APK signing certificate, then loads your
classes **into its own process**. There is no OS sandbox between your code and Relay's. That trust
is why the rules below are absolute rather than stylistic.

Your extension **may**: make its own network requests, parse responses, keep in-memory caches, and
declare bounded settings.

Your extension **must not**: read or write Relay's database or files, request Android permissions
beyond `INTERNET`, embed credentials or API secrets, load code at runtime, spawn long-lived
threads, or block indefinitely. Relay bounds every call it makes to you, but bad behaviour inside
that window is still your responsibility.

---

## 3. The ABI (source API version 2)

Extend `BaseRelaySource`. **Never implement the `RelaySource` interface directly** — see §9 for why.

| Member | Required | Contract |
| --- | --- | --- |
| `getId()` | yes | Stable source id, `[a-z0-9][a-z0-9._-]{0,127}`. Never change it after release: Relay keys library records on it. |
| `getName()` | yes | Human-readable, ≤128 chars. |
| `search(query, page)` | yes | `page` starts at 1. Empty query should browse a sensible default rather than returning everything. Field-scoped queries arrive as `title:`, `artist:`, or `album:` prefixes — strip them or honour them. |
| `getListings()` | no | Browse shelves shown before any query (max 24). Ids follow `[a-z0-9][a-z0-9._-]{0,63}`. |
| `browse(listingId, page)` | if you declare listings | One page of a listing. |
| `resolveStreamUrl(trackId)` | see §4 | Called immediately before playback. |
| `resolveArtworkUrl(trackId)` | no | Lazy per-track artwork. Never fire one request per search row. |
| `resolveDownloadUrl(trackId)` | no | Only when downloads use a different endpoint than streaming. Relay falls back to the stream URL, then to the track's own url. |
| `getMediaRequestHeaders()` | no | Headers Relay attaches to stream, artwork, and download requests. |
| `getSettings()` / `applySettings(values)` | no | User-editable preferences, rendered by Relay (max 16). |

`RelaySourcePage(tracks, hasNextPage)` — set `hasNextPage` only when a further page exists. A
common honest approximation is `results.size == pageSize`.

`RelaySourceTrack(id, streamUrl, title, artist, album, durationMs, artworkUrl)` — `id` must be
stable for the source; `title` and `artist` must be non-blank or Relay drops the row.

---

## 4. Eager or lazy streams — pick deliberately

**Eager** (`streamUrl` filled in on every track, no `resolveStreamUrl`): correct when the search
response already contains a durable file URL. One request per page, instant playback.

**Lazy** (`streamUrl = null`, implement `resolveStreamUrl`): correct when URLs are short-lived,
signed, or need an extra page fetch per track. Relay stores a `relay-extension://` placeholder and
calls you once, immediately before the track loads — so nothing stale is ever persisted.

Scraped sources are almost always lazy. Do not fabricate a URL at search time that will have
expired by the time it is played.

---

## 5. Media request headers

Relay forwards only these header names, max 8, values ≤4096 chars, no CR/LF:

```
User-Agent   Referer   Origin   Cookie   Authorization   Accept
```

Anything else is dropped silently. Declare an identifiable `User-Agent`. If §1.4 showed the host
needs a `Referer`, return it here — that is exactly what this hook is for.

---

## 6. Project layout

```
<name>-source/
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/kotlin/org/relay/extensions/<name>/<Name>Source.kt
```

Add `include(":<name>-source:app")` to `settings.gradle.kts`.

`build.gradle.kts`:

```kotlin
plugins { id("com.android.application") }

android {
    namespace = "org.relay.extensions.<name>"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.relay.extensions.<name>"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // Relay parent-loads the API: do not package a duplicate in the source APK.
    compileOnly(project(":relay-source-api"))
}
```

`AndroidManifest.xml` — the three `meta-data` values are how Relay finds your class:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="dev.relay.music.source.extension" android:required="false" />

    <application android:label="<Display name>">
        <meta-data android:name="relay.source.api" android:value="2" />
        <meta-data android:name="relay.source.id" android:value="org.relay.extensions.<name>" />
        <meta-data android:name="relay.source.class" android:value=".<Name>SourceFactory" />
    </application>
</manifest>
```

`relay.source.id` must equal the catalogue entry id, and `relay.source.api` must equal the API
version you compiled against, or Relay refuses to enable the extension with a stated reason.

---

## 7. Skeleton

```kotlin
class ExampleSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION
    override fun createSources(): List<RelaySource> = listOf(ExampleSource())
}

private class ExampleSource : BaseRelaySource() {
    @Volatile private var pageSize = 20

    override fun getId() = "example"
    override fun getName() = "Example"

    override fun getListings() = listOf(RelaySourceListing("recent", "Recent"))

    override fun browse(listingId: String, page: Int): RelaySourcePage =
        query("sort=date", page)

    override fun search(query: String, page: Int): RelaySourcePage {
        val term = query.removeFieldPrefix().trim()
        return if (term.isEmpty()) query("sort=date", page) else query("q=${term.encoded()}", page)
    }

    override fun getSettings() = listOf(
        RelaySourceSetting("page-size", "Results per page",
            RelaySourceSetting.Type.CHOICE, "20", listOf("10", "20", "40")),
    )

    override fun applySettings(values: Map<String, String>) {
        values["page-size"]?.toIntOrNull()?.takeIf { it in 1..50 }?.let { pageSize = it }
    }

    override fun getMediaRequestHeaders() = mapOf("User-Agent" to USER_AGENT)
}
```

Fetch helper rules: set connect/read timeouts (10s/15s is typical), send your `User-Agent`, check
the response code, and **bound the response size** while reading — never `readText()` an unbounded
stream from a host you do not control.

---

## 8. What the host rejects

Relay validates everything you return and disables a misbehaving source with a visible reason.

- URLs must be HTTPS and ≤8192 chars; track ids ≤512 chars.
- `title` and `artist` non-blank, ≤1024 chars; `durationMs` within 1 ms…24 h.
- ≤100 tracks per page, ≤24 listings, ≤16 settings, ≤32 sources per APK.
- Queries arrive ≤256 chars; pages 1…1000.
- Timeouts: 5 s for load/listings/artwork/settings, 10 s for search, browse, stream and download
  resolution.
- Duplicate source ids or listing ids inside one APK are refused.

---

## 9. Failure modes seen in practice

- **`Failed resolution of RelaySource$-CC`** — you implemented the interface directly and relied on
  interface default methods. Android desugars those into a `$-CC` companion that is private to each
  dex compilation, so it does not resolve across the APK boundary. **Extend `BaseRelaySource`.**
- **`NoSuchMethodError` on a JDK collection** — `List.copyOf`, `Map.of`, and friends are API 31+ on
  Android while extensions target `minSdk 23`. Use `ArrayList`/`Collections.unmodifiableList`.
- **Silent 403 on audio** — the API worked, the media host has hotlink protection. See §1.4 and §5.
- **Search timing out** — you are making one request per result. Redesign around a track-shaped
  endpoint or make the work lazy.
- **Tracks vanish after an app restart** — you returned unstable ids. Relay keys playlists, history,
  and downloads on `(sourceId, trackId)`; they must survive restarts.

---

## 10. Verify before publishing

Do not publish on a green build alone.

```sh
# 1. builds
./gradlew :<name>-source:app:assembleDebug

# 2. the API returns what you think, including page 2
curl -s "<api url page 1>" | head -c 400
curl -s "<api url page 2>" | head -c 400

# 3. the media actually fetches with the headers you declare  (§1.4)
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -L -A "<your UA>" "<file url>"

# 4. manifest metadata is what Relay looks for
aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep -A1 relay.source
```

Then install through Relay: **Extensions → REFRESH → AVAILABLE → your extension → INSTALL APK**,
and confirm on the device that browse listings load, search paginates with LOAD MORE, a track
plays, and DOWNLOAD saves a file. An extension that lists tracks but cannot play them is not done.

---

## 11. Publish

```sh
shasum -a 256 <apk>          # sha256 for the catalogue
stat -f%z <apk>              # artifactSizeBytes
```

Add the entry to `index.json`:

```json
{
  "id": "org.relay.extensions.<name>",
  "name": "<Display name>",
  "version": "0.1.0",
  "kind": "SOURCE",
  "api": { "minimum": 2, "maximum": 2 },
  "artifactUrl": "https://github.com/<owner>/relay-extensions/releases/download/<name>-v0.1.0/app-debug.apk",
  "sha256": "<sha256>",
  "artifactSizeBytes": <bytes>,
  "permissions": ["NETWORK"],
  "androidPackageName": "org.relay.extensions.<name>",
  "androidSigningCertificateSha256": "<apk signer sha256, lowercase hex>"
}
```

The signer must match the fingerprint already trusted for this repository — Relay treats a signer
change as a trust break and refuses the install. Then:

```sh
sh scripts/sign-index.sh                     # re-sign the catalogue (required)
git commit -am "Add <name> source extension" && git push
gh release create <name>-v0.1.0 <apk> --title "<name> source v0.1.0" --notes "..."
```

Bump `versionCode`, `versionName`, `sha256`, and `artifactSizeBytes` together on every release, and
re-sign the index. A digest that does not match the artifact fails closed at install time.

### Making your repository one-tap installable

Relay registers `relay://add-repo?url=<url-encoded descriptor url>`. Opening that link fetches the
descriptor and opens Relay's importer for review — it never trusts a repository on its own, because
a web page must not be able to add a signing identity.

GitHub strips non-web schemes from rendered Markdown, so the link cannot go in `README.md`. Serve a
small HTML page instead (this repository uses `docs/index.html` with GitHub Pages) containing:

```html
<a href="relay://add-repo?url=https%3A%2F%2Fraw.githubusercontent.com%2F<owner>%2F<repo>%2Fmain%2Frepository.json">Add to Relay</a>
```

Always print the plain descriptor URL on the same page as a fallback for desktop visitors and for
anyone who does not have Relay installed.
