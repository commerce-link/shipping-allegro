package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.Carrier;
import pl.commercelink.shipping.api.ShippingException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllegroCarriersTest {

    @Mock
    private RestApiWithRetry restApi;

    private AllegroShipping allegro() {
        return new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6);
    }

    @Test
    void carriersAreTheDistinctCarriersOfPolishDeliveryServices() {
        // given
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-services"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("delivery-services.json"));

        // when
        List<Carrier> carriers = allegro().getAvailableCarriers();

        // then: the sandbox list has 299 services, 118 of them for allegro-pl, from these eight carriers
        assertEquals(List.of("ALLEGRO", "DHL", "DPD", "DPD_CZ", "DPD_HU", "DPD_SK", "PACKETA", "POCZTA_POLSKA"),
                carriers.stream().map(Carrier::id).sorted().toList());
    }

    @Test
    void missingShipmentsConsentIsReportedWithTheForbiddenAnswerAsCause() {
        // given
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-services"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(403, Recordings.text("forbidden.json")));

        // when
        ShippingException e = assertThrows(ShippingException.class, () -> allegro().getAvailableCarriers());

        // then
        assertEquals(AllegroErrors.NO_SHIPMENTS_CONSENT, e.getMessage());
        assertEquals(403, ((HttpClientException) e.getCause()).getStatusCode());
        assertEquals(List.of("No access to the resource"), e.providerMessages());
    }

    @Test
    void serverErrorKeepsTheHttpCause() {
        // given
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-services"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(503, "<html>Service Unavailable</html>"));

        // when
        ShippingException e = assertThrows(ShippingException.class, () -> allegro().getAvailableCarriers());

        // then
        assertEquals(503, ((HttpClientException) e.getCause()).getStatusCode());
        assertTrue(e.providerMessages().isEmpty());
    }
}
