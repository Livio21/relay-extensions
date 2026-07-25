package dev.relay.music.source.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A source owns its own site/API requests and returns normalised records to Relay. */
public interface RelaySource {
    String getId();

    String getName();

    /**
     * Browse listings offered before any search, for example "Popular" or "Recently added".
     * An empty list means the source is search-only.
     */
    default List<RelaySourceListing> getListings() {
        return Collections.emptyList();
    }

    /**
     * Empty text browses the source's default view; field searches use {@code title:},
     * {@code artist:}, or {@code album:}. Page numbering starts at 1. Return
     * {@link RelaySourcePage#getHasNextPage()} {@code true} only when the next page exists.
     */
    RelaySourcePage search(String query, int page) throws Exception;

    /** Browse one listing returned by {@link #getListings()}. Page numbering starts at 1. */
    default RelaySourcePage browse(String listingId, int page) throws Exception {
        throw new UnsupportedOperationException("Source has no listings.");
    }

    /**
     * Just-in-time stream lookup, called immediately before playback or download. A source that
     * cannot cheaply include {@code streamUrl} in search results (short-lived URLs, one page
     * fetch per track) returns tracks without one and resolves here instead.
     */
    default String resolveStreamUrl(String trackId) throws Exception {
        return null;
    }

    /** Optional lazy artwork lookup for a selected track. Search results must not trigger one request per row. */
    default String resolveArtworkUrl(String trackId) throws Exception {
        return null;
    }

    /**
     * Optional just-in-time download URL lookup. Hosts fall back to the resolved stream URL
     * when a source does not need a separate download endpoint.
     */
    default String resolveDownloadUrl(String trackId) throws Exception {
        return null;
    }

    /**
     * Extra HTTP headers Relay attaches when it fetches this source's streams, artwork, and
     * downloads, for example {@code Referer} or a site-required {@code User-Agent}. Relay only
     * forwards a bounded allow-list of header names.
     */
    default Map<String, String> getMediaRequestHeaders() {
        return Collections.emptyMap();
    }

    /** User-editable source preferences, rendered by Relay's own settings UI. Max 16. */
    default List<RelaySourceSetting> getSettings() {
        return Collections.emptyList();
    }

    /**
     * Stored preference values, applied right after Relay loads the source and again when the
     * user edits a value. Keys match {@link RelaySourceSetting#getId()}; unknown keys must be
     * ignored. Values were never seen by other extensions.
     */
    default void applySettings(Map<String, String> values) {
    }
}
