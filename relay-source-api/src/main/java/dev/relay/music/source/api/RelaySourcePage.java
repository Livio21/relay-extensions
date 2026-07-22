package dev.relay.music.source.api;

import java.util.List;

public final class RelaySourcePage {
    private final List<RelaySourceTrack> tracks;
    private final boolean hasNextPage;

    public RelaySourcePage(List<RelaySourceTrack> tracks) {
        this(tracks, false);
    }

    public RelaySourcePage(List<RelaySourceTrack> tracks, boolean hasNextPage) {
        this.tracks = List.copyOf(tracks);
        this.hasNextPage = hasNextPage;
    }

    public List<RelaySourceTrack> getTracks() { return tracks; }
    public boolean getHasNextPage() { return hasNextPage; }
}

