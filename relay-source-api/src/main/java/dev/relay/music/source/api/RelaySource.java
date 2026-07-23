package dev.relay.music.source.api;

/** A source owns its own public-site/API requests and returns normalized records to Relay. */
public interface RelaySource {
    String getId();
    String getName();

    /** Empty text browses; field searches use {@code title:}, {@code artist:}, or {@code album:}. */
    RelaySourcePage search(String query) throws Exception;

    /** Optional lazy artwork lookup for a selected track. Search results must not trigger one request per row. */
    default String resolveArtworkUrl(String trackId) throws Exception {
        return null;
    }
}
