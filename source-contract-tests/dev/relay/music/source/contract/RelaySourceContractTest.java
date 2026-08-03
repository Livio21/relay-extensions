package dev.relay.music.source.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.relay.music.source.api.BaseRelaySource;
import dev.relay.music.source.api.RelaySource;
import dev.relay.music.source.api.RelaySourceApi;
import dev.relay.music.source.api.RelaySourceFactory;
import dev.relay.music.source.api.RelaySourceListing;
import dev.relay.music.source.api.RelaySourceSetting;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Offline checks shared by every real source before its APK is published. */
public abstract class RelaySourceContractTest {
    private static final Set<String> ALLOWED_HEADERS = Set.of(
        "user-agent", "referer", "origin", "cookie", "authorization", "accept"
    );

    protected abstract RelaySourceFactory createFactory();

    @Test
    public final void staticSourceContractStaysWithinHostBounds() {
        RelaySourceFactory factory = createFactory();
        assertEquals(RelaySourceApi.VERSION, factory.getApiVersion());
        List<RelaySource> sources = factory.createSources();
        assertNotNull(sources);
        assertFalse(sources.isEmpty());
        assertTrue(sources.size() <= 32);

        Set<String> sourceIds = new HashSet<>();
        for (RelaySource source : sources) {
            assertTrue("Sources must extend BaseRelaySource", source instanceof BaseRelaySource);
            assertTrue(source.getId().matches("[a-z0-9][a-z0-9._-]{0,127}"));
            assertTrue(sourceIds.add(source.getId()));
            assertFalse(source.getName().isBlank());
            assertTrue(source.getName().length() <= 128);
            assertListings(source.getListings());
            Map<String, String> defaults = assertSettings(source.getSettings());
            source.applySettings(defaults);
            source.applySettings(Map.of("relay-unknown-setting", "ignored"));
            assertHeaders(source.getMediaRequestHeaders());
        }
    }

    private static void assertListings(List<RelaySourceListing> listings) {
        assertNotNull(listings);
        assertTrue(listings.size() <= 24);
        Set<String> ids = new HashSet<>();
        for (RelaySourceListing listing : listings) {
            assertTrue(listing.getId().matches("[a-z0-9][a-z0-9._-]{0,63}"));
            assertTrue(ids.add(listing.getId()));
            assertFalse(listing.getName().isBlank());
            assertTrue(listing.getName().length() <= 64);
        }
    }

    private static Map<String, String> assertSettings(List<RelaySourceSetting> settings) {
        assertNotNull(settings);
        assertTrue(settings.size() <= 16);
        Set<String> ids = new HashSet<>();
        Map<String, String> defaults = new HashMap<>();
        for (RelaySourceSetting setting : settings) {
            assertTrue(setting.getId().matches("[a-z0-9][a-z0-9._-]{0,63}"));
            assertTrue(ids.add(setting.getId()));
            assertFalse(setting.getLabel().isBlank());
            assertTrue(setting.getLabel().length() <= 64);
            assertNotNull(setting.getType());
            assertNotNull(setting.getDefaultValue());
            assertTrue(setting.getDefaultValue().length() <= 1_024);
            assertNotNull(setting.getChoices());
            if (setting.getType() == RelaySourceSetting.Type.CHOICE) {
                assertFalse(setting.getChoices().isEmpty());
                assertTrue(setting.getChoices().size() <= 16);
                assertTrue(setting.getChoices().contains(setting.getDefaultValue()));
            } else {
                assertTrue(setting.getChoices().isEmpty());
            }
            defaults.put(setting.getId(), setting.getDefaultValue());
        }
        return defaults;
    }

    private static void assertHeaders(Map<String, String> headers) {
        assertNotNull(headers);
        assertTrue(headers.size() <= 8);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            assertTrue(ALLOWED_HEADERS.contains(header.getKey().toLowerCase()));
            assertFalse(header.getValue().isBlank());
            assertTrue(header.getValue().length() <= 4_096);
            assertFalse(header.getValue().contains("\r"));
            assertFalse(header.getValue().contains("\n"));
        }
    }
}
