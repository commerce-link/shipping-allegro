package pl.commercelink.shipping.allegro;

import pl.commercelink.provider.api.AuthConfig;
import pl.commercelink.provider.api.ProviderField;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.ShippingProvider;
import pl.commercelink.shipping.api.ShippingProviderDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static pl.commercelink.provider.api.ProviderField.FieldType.TEXT;

/**
 * "Wysyłam z Allegro". Has no credentials of its own: the application hands it a RestApiWithRetry built on the store's
 * Allegro marketplace tokens, so the OAuth2 settings below must stay identical to the marketplace-allegro descriptor
 * (same token endpoint, same refresh lifetime, same environment switches for the sandbox).
 */
public class AllegroShippingProviderDescriptor implements ShippingProviderDescriptor {

    public static final String LABEL_FORMAT = "labelFormat";

    static final String DEFAULT_API_URL = "https://api.allegro.pl";
    static final String DEFAULT_TOKEN_URL = "https://allegro.pl/auth/oauth/token";
    static final String DEFAULT_DEVICE_URL = "https://allegro.pl/auth/oauth/device";
    static final String ACCEPT_HEADER = "application/vnd.allegro.public.v1+json";

    @Override
    public String name() {
        return "allegro";
    }

    @Override
    public String displayName() {
        return "Wysyłam z Allegro";
    }

    @Override
    public List<ProviderField> configurationFields() {
        return List.of(new ProviderField(LABEL_FORMAT, "Format etykiety", TEXT, false, AllegroLabelFormat.PDF_A6.name()));
    }

    @Override
    public AuthConfig authConfig() {
        String tokenUrl = resolve("ALLEGRO_TOKEN_URL", DEFAULT_TOKEN_URL);
        return new AuthConfig.OAuth2(
                resolve("ALLEGRO_API_URL", DEFAULT_API_URL),
                tokenUrl,
                tokenUrl,
                90L * 24 * 60 * 60,
                ACCEPT_HEADER,
                null,
                ACCEPT_HEADER,
                resolve("ALLEGRO_DEVICE_URL", DEFAULT_DEVICE_URL));
    }

    @Override
    public ShippingProvider create(Map<String, String> configuration) {
        throw new UnsupportedOperationException("Wysyłam z Allegro requires the OAuth2 context of the Allegro marketplace");
    }

    @Override
    public ShippingProvider create(Map<String, String> configuration, Map<String, Object> context) {
        RestApiWithRetry restApi = (RestApiWithRetry) Objects.requireNonNull(
                context.get("restApi"), "restApi missing in provider context");
        String labelFormat = configuration == null ? null : configuration.get(LABEL_FORMAT);
        return new AllegroShipping(restApi, AllegroLabelFormat.from(labelFormat));
    }

    private static String resolve(String key, String defaultValue) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv(key);
        return env != null && !env.isBlank() ? env : defaultValue;
    }
}
