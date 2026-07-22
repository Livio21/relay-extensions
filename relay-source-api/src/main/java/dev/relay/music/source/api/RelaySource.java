package dev.relay.music.source.api;

/** A source owns its own public-site/API requests and returns normalized records to Relay. */
public interface RelaySource {
    String getId();
    String getName();

    /** Empty text browses; field searches use {@code title:}, {@code artist:}, or {@code album:}. */
    RelaySourcePage search(String query) throws Exception;
}

