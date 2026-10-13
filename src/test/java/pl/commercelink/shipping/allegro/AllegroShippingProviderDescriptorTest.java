package pl.commercelink.shipping.allegro;

import org.junit.jupiter.api.Test;
import pl.commercelink.provider.api.AuthConfig;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.ShippingProvider;
import pl.commercelink.shipping.api.ShippingProviderDescriptor;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AllegroShippingProviderDescriptorTest {

    @Test
    void descriptorIsFoundByServiceLoaderUnderTheNameAllegro() {
        // when
        boolean found = ServiceLoader.load(ShippingProviderDescriptor.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(d -> d instanceof AllegroShippingProviderDescriptor && "allegro".equals(d.name()));

        // then
        assertTrue(found);
    }

    @Test
    void authUsesTheSameAllegroOAuthAsTheMarketplace() {
        // when
        AuthConfig.OAuth2 auth = (AuthConfig.OAuth2) new AllegroShippingProviderDescriptor().authConfig();

        // then
        assertEquals("https://api.allegro.pl", auth.apiUrl());
        assertEquals("https://allegro.pl/auth/oauth/token", auth.authEndpointPath());
        assertEquals("https://allegro.pl/auth/oauth/token", auth.refreshEndpointPath());
        assertEquals(90L * 24 * 60 * 60, auth.refreshTokenExpirationSeconds());
        assertEquals("application/vnd.allegro.public.v1+json", auth.acceptHeader());
        assertEquals("application/vnd.allegro.public.v1+json", auth.contentTypeHeader());
        assertEquals("https://allegro.pl/auth/oauth/device", auth.deviceAuthUrl());
    }

    @Test
    void createBuildsTheProviderFromTheRestApiInTheContext() {
        // when
        ShippingProvider provider = new AllegroShippingProviderDescriptor().create(
                Map.of(AllegroShippingProviderDescriptor.LABEL_FORMAT, "ZPL"),
                Map.of("restApi", mock(RestApiWithRetry.class)));

        // then
        assertInstanceOf(AllegroShipping.class, provider);
        assertEquals(AllegroLabelFormat.ZPL, ((AllegroShipping) provider).labelFormat());
    }

    @Test
    void createWithoutContextIsRefused() {
        // when / then
        assertThrows(UnsupportedOperationException.class,
                () -> new AllegroShippingProviderDescriptor().create(Map.of()));
    }

    @Test
    void unknownOrMissingLabelFormatFallsBackToPdfA6() {
        // when / then
        assertEquals(AllegroLabelFormat.PDF_A6, AllegroLabelFormat.from(null));
        assertEquals(AllegroLabelFormat.PDF_A6, AllegroLabelFormat.from(" "));
        assertEquals(AllegroLabelFormat.PDF_A6, AllegroLabelFormat.from("EPL"));
        assertEquals(AllegroLabelFormat.PDF_A4, AllegroLabelFormat.from("pdf_a4"));
    }

    @Test
    void labelFormatsMapToAllegroFileFormatAndPageSize() {
        // when / then
        assertEquals("PDF", AllegroLabelFormat.PDF_A4.fileFormat());
        assertEquals("A4", AllegroLabelFormat.PDF_A4.pageSize());
        assertEquals("ZPL", AllegroLabelFormat.ZPL.fileFormat());
        assertEquals("A6", AllegroLabelFormat.ZPL.pageSize());
    }
}
