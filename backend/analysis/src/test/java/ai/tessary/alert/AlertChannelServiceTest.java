// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertChannelDtos.UpsertChannelRequest;
import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Channel creation's refusals, each before anything is stored. The bugs: a channel saved with no config
 * or an unknown kind (it would fail every delivery later, far from the person who made it), credentials
 * stored in the clear or not at all when no secret key is configured, and a config that cannot be sealed
 * escaping as a raw 500 instead of the named config error. The CRUD happy paths run against the database
 * in {@code AlertChannelControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
class AlertChannelServiceTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock
    AlertChannelRepository repo;

    @Mock
    DeliveryAttemptRepository attempts;

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void aChannelThatCannotBeStoredSafelyIsRefused(
            String why, String kind, @Nullable Map<String, Object> config, String secretKey, AlertError expected) {
        TessaryProperties props = new TessaryProperties();
        props.setSecretKey(secretKey);
        AlertChannelService service = new AlertChannelService(repo, attempts, new SecretBox(props), new ObjectMapper());

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> service.create("p1", new UpsertChannelRequest(kind, "ops", true, config)));

        assertEquals(expected, e.error());
    }

    static Stream<Arguments> refusals() {
        Map<String, Object> url = Map.of("url", "https://203.0.113.10/hook");
        return Stream.of(
                Arguments.of("no secret key", "webhook", url, "", AlertError.MISSING_SECRET_KEY),
                Arguments.of("unknown kind", "carrier-pigeon", url, KEY, AlertError.UNSUPPORTED_CHANNEL),
                Arguments.of("absent config", "webhook", null, KEY, AlertError.INVALID_CHANNEL_CONFIG),
                Arguments.of("empty config", "webhook", Map.of(), KEY, AlertError.INVALID_CHANNEL_CONFIG),
                Arguments.of(
                        "unserialisable config",
                        "webhook",
                        Map.of("url", new Object()),
                        KEY,
                        AlertError.INVALID_CHANNEL_CONFIG));
    }
}
