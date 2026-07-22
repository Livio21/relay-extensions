package dev.relay.music.source.api;

import java.util.List;

public interface RelaySourceFactory {
    int getApiVersion();
    List<RelaySource> createSources();
}

