package dev.relay.music.source.api;

public final class RelaySourceTrack {
    private final String id;
    private final String streamUrl;
    private final String title;
    private final String artist;
    private final String album;
    private final Long durationMs;
    private final String artworkUrl;

    public RelaySourceTrack(String id, String streamUrl, String title, String artist, String album, Long durationMs, String artworkUrl) {
        this.id = id;
        this.streamUrl = streamUrl;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.artworkUrl = artworkUrl;
    }

    public String getId() { return id; }
    public String getStreamUrl() { return streamUrl; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public Long getDurationMs() { return durationMs; }
    public String getArtworkUrl() { return artworkUrl; }
}

