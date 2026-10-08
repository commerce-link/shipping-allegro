package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.ShipmentCancellation;
import pl.commercelink.shipping.api.ShippingException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllegroCancellationTest {

    private static final String SHIPMENT = "b7c6b01a-2916-4ae7-ae3d-570140674e25";
    private static final String CANCEL = "/shipment-management/shipments/cancel-commands";

    @Mock
    private RestApiWithRetry restApi;

    private AllegroShipping allegro() {
        return new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6);
    }

    @Test
    void cancelSendsACommandUnderTheCallersIdAndIsPending() {
        // when
        ShipmentCancellation started = allegro().cancelShipment(SHIPMENT, "cancel-1");

        // then
        assertEquals(ShipmentCancellation.Status.PENDING, started.status());
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restApi).postWithAuthRetry(eq(CANCEL), body.capture(), anyMap(), eq(JsonNode.class));
        JsonNode sent = AllegroJson.MAPPER.valueToTree(body.getValue());
        assertEquals("cancel-1", sent.path("commandId").asText());
        assertEquals(SHIPMENT, sent.path("input").path("shipmentId").asText());
    }

    @Test
    void cancelRefusedByAllegroThrowsWithTheHttpCause() {
        // given
        when(restApi.postWithAuthRetry(eq(CANCEL), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(422, "{\"errors\":[{\"userMessage\":\"Przesyłka została już odebrana\"}]}"));

        // when
        ShippingException e = assertThrows(ShippingException.class, () -> allegro().cancelShipment(SHIPMENT, "cancel-1"));

        // then
        assertEquals("Przesyłka została już odebrana", e.getMessage());
        assertInstanceOf(HttpClientException.class, e.getCause());
    }

    @Test
    void cancelWithServerErrorStaysPending() {
        // given
        when(restApi.postWithAuthRetry(eq(CANCEL), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(500, ""));

        // when / then
        assertEquals(ShipmentCancellation.Status.PENDING, allegro().cancelShipment(SHIPMENT, "cancel-1").status());
    }

    @Test
    void cancelCommandAlreadyKnownToAllegroStaysPending() {
        // given
        when(restApi.postWithAuthRetry(eq(CANCEL), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(409, ""));

        // when / then
        assertEquals(ShipmentCancellation.Status.PENDING, allegro().cancelShipment(SHIPMENT, "cancel-1").status());
    }

    @Test
    void cancelCommandIdAllegroAlreadyHasStaysPending() {
        // given: the sandbox answers a cancel command id sent twice with HTTP 400 COMMAND_EXISTS
        when(restApi.postWithAuthRetry(eq(CANCEL), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(400, Recordings.text("create-command-duplicate.json")));

        // when / then
        assertEquals(ShipmentCancellation.Status.PENDING, allegro().cancelShipment(SHIPMENT, "cancel-1").status());
    }

    @Test
    void successfulCancelCommandSucceeds() {
        // given
        when(restApi.fetchWithAuthRetry(eq(CANCEL + "/cancel-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("cancel-command-success.json"));

        // when
        ShipmentCancellation done = allegro().checkShipmentCancellation("cancel-1", SHIPMENT);

        // then
        assertEquals(ShipmentCancellation.Status.SUCCEEDED, done.status());
        assertTrue(done.otherCancelledPackageIds().isEmpty());
    }

    @Test
    void failedCancelCommandStatesAllegrosReason() {
        // given: the 2026-10-06 sandbox answer for an unknown shipment
        when(restApi.fetchWithAuthRetry(eq(CANCEL + "/cancel-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("cancel-command-error.json"));

        // when
        ShipmentCancellation done = allegro().checkShipmentCancellation("cancel-1", SHIPMENT);

        // then
        assertEquals(ShipmentCancellation.Status.FAILED, done.status());
        assertEquals("Carriage not found", done.error());
    }

    @Test
    void oneByAllegroCancelIsRefusedWithAllegrosReason() {
        // given: the 2026-10-08 sandbox answer for a One by Allegro shipment
        when(restApi.fetchWithAuthRetry(eq(CANCEL + "/cancel-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("cancel-command-error-one.json"));

        // when
        ShipmentCancellation done = allegro().checkShipmentCancellation("cancel-1", SHIPMENT);

        // then
        assertEquals(ShipmentCancellation.Status.FAILED, done.status());
        assertEquals("Nie można anulować przesyłki u przewoźnika Allegro", done.error());
    }

    @Test
    void unknownCancelCommandIsPending() {
        // given
        when(restApi.fetchWithAuthRetry(eq(CANCEL + "/cancel-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(404, ""));

        // when / then
        assertEquals(ShipmentCancellation.Status.PENDING, allegro().checkShipmentCancellation("cancel-1", SHIPMENT).status());
    }
}
