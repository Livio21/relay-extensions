# Octave Relay source — proof of concept

This is a **test-only proof of concept** in the signed Relay catalog. It is not a general-purpose
Octave client or a marketplace recommendation.

It deliberately does only one thing: search Relay for a numeric Octave track ID, then resolve the
documented audio endpoint at playback time. It does not scrape pages, browse a catalogue, infer
metadata, save a key in the APK, or use account credentials.

## Test through Relay

1. Refresh the `Livio21/relay-extensions` repository in Relay, then install **Octave (proof of concept)**.
2. In the source settings, enter only the public playback key supplied by Octave for development.
   Do not enter account passwords, bearer tokens, or client secrets.
3. Search by title, artist, album, or a numeric track ID. Octave's public search response supplies
   the title, artist, album, duration, and album artwork; the playback key is used only when Relay
   resolves the stream immediately before playback.

The key is user configuration and is intentionally absent from this repository. This POC must not
be used for downloads.
