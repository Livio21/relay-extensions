# Relay source extensions

This repository is a signed Relay source catalog of Android music source extensions.

## Add it to Relay

On the phone that has Relay installed, open **[the install page](https://livio21.github.io/relay-extensions/)** and press **Add to Relay**. Relay fetches the descriptor and shows its signing-key fingerprint; nothing is trusted, installed, or enabled until you accept it.

To add it by hand instead, paste either of these into Relay's **Settings → Source repositories**:

```
Livio21/relay-extensions
https://raw.githubusercontent.com/Livio21/relay-extensions/main/repository.json
```

> The install page needs GitHub Pages enabled for this repository (**Settings → Pages → Deploy from branch → `main` / `/docs`**). The `relay://add-repo` link cannot live in this README directly: GitHub strips non-web URL schemes from rendered Markdown.

## Included sources

- **Free Music Archive** — searches Free Music Archive's public music pages and returns its public stream redirect URLs. It neither logs in nor circumvents access controls. Music availability and licences are determined by Free Music Archive per track; check the linked FMA page before reusing a work.
- **ccMixter** — queries ccMixter's public JSON API for Creative Commons remixes and originals. ccMixter's media host rejects requests without a `Referer` from its own site, so the source declares one through `getMediaRequestHeaders()`; Relay attaches it to every stream, artwork, and download fetch.

## Writing an extension

[EXTENSION_AUTHORING.md](EXTENSION_AUTHORING.md) is a complete brief for building, verifying, and publishing a source extension — the ABI, the trust rules, the host's validation limits, the failure modes we have actually hit, and the verification steps to run before publishing. It is written to be handed to a coding agent as-is.

## Repository maintainers

The catalog is signed with a local ECDSA P-256 key. Run `scripts/create-signing-key.sh` once, retain `keys/repository-private.pem` securely outside Git, and run `scripts/sign-index.sh` after every `index.json` change. The repository public key is published in `repository.json`.

Each source is a separate Android APK that Relay verifies against the signed catalog before loading. Source APKs use the Relay source API as a compile-only dependency, and must only use documented APIs or public pages users can normally access.

The initial FMA artifact is a test build. Before distributing it beyond personal testing, configure a private release APK signing key and update the catalog's APK certificate digest.

