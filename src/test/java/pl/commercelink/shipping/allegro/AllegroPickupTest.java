package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllegroPickupTest {

    private static final String A = "9a1b2c3d-1111-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String B = "5b4a3c2d-3333-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String DPD = "b7c6b01a-2916-4ae7-ae3d-570140674e25";
    private static final String ONE = "dde12f96-34d6-4513-8321-2d47a6b5251e";
    private static final String PROPOSALS = "/shipment-management/pickup-proposals";
    private static final String COMMANDS = "/shipment-management/pickups/create-commands";

    @Mock
    private RestApiWithRetry restApi;

    private AllegroShipping allegro() {
        return new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6);
    }

    private static ShipmentAddress warehouse() {
        return new ShipmentAddress("Magazyn Sklepu", "Sklep sp. z o.o.", "Magazynowa 3", "50-001", "Wrocław", "PL",
                "magazyn@sklep.pl", "600700800");
    }

    @Test
    void allegroSupportsPickups() {
        assertTrue(allegro().supportsPickups());
    }

    @Test
    void windowsOfOneShipmentAreAllegrosWithinTheDaysAheadSorted() {
        // given: the 2026-10-08 sandbox answer for an Allegro DPD shipment
        when(restApi.postWithAuthRetry(eq(PROPOSALS), any(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-proposals.json"));

        // when
        List<PickupWindow> windows = allegro().pickupWindows(List.of(DPD), warehouse(), LocalDate.of(2026, 10, 8), 4);

        // then
        assertEquals(List.of(
                new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(9, 0), LocalTime.of(17, 0), "2026-10-09|09:00|17:00"),
                new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(11, 0), LocalTime.of(14, 0), "2026-10-09|11:00|14:00"),
                new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0), "2026-10-09|14:00|17:00")),
                windows);
    }

    @Test
    void windowsAreThoseCommonToAllShipmentsWithinTheDaysAheadSorted() {
        // given: synthetic, two shipments of one courier
        when(restApi.postWithAuthRetry(eq(PROPOSALS), any(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-proposals-two-shipments.json"));

        // when
        List<PickupWindow> windows = allegro().pickupWindows(List.of(A, B), warehouse(), LocalDate.of(2026, 10, 8), 4);

        // then: 10.10 is offered for A only, 20.10 is past the 4 days ahead
        assertEquals(List.of(
                new PickupWindow(LocalDate.of(2026, 10, 8), LocalTime.of(10, 0), LocalTime.of(14, 0), "2026-10-08|10:00|14:00"),
                new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0), "2026-10-09|14:00|17:00")),
                windows);
    }

    @Test
    void shipmentsOfDifferentCouriersHaveNoCommonWindow() {
        // given: the 2026-10-08 sandbox answer for a DPD and a One by Allegro shipment, one group per courier
        when(restApi.postWithAuthRetry(eq(PROPOSALS), any(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-proposals-two-carriers.json"));

        // when / then
        assertTrue(allegro().pickupWindows(List.of(DPD, ONE), warehouse(), LocalDate.of(2026, 10, 8), 4).isEmpty());
    }

    @Test
    void proposalsAreAskedForWithTheShipmentsReadyDateAndAddress() {
        // given
        when(restApi.postWithAuthRetry(eq(PROPOSALS), any(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-proposals.json"));

        // when
        allegro().pickupWindows(List.of(A), warehouse(), LocalDate.of(2026, 10, 8), 4);

        // then
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restApi).postWithAuthRetry(eq(PROPOSALS), body.capture(), anyMap(), eq(JsonNode.class));
        JsonNode sent = AllegroJson.MAPPER.valueToTree(body.getValue());
        assertEquals(A, sent.path("shipmentIds").get(0).asText());
        assertEquals("2026-10-08", sent.path("readyDate").asText());
        assertEquals("Magazynowa 3", sent.path("address").path("street").asText());
        assertEquals("50-001", sent.path("address").path("postalCode").asText());
    }

    @Test
    void shipmentMissingFromTheAnswerLeavesNoCommonWindow() {
        // given
        when(restApi.postWithAuthRetry(eq(PROPOSALS), any(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-proposals.json"));

        // when / then
        assertTrue(allegro().pickupWindows(List.of(DPD, "unknown"), warehouse(), LocalDate.of(2026, 10, 8), 4).isEmpty());
    }

    @Test
    void refusedProposalsAreAShippingExceptionWithTheHttpCause() {
        // given: the 2026-10-06 sandbox answer for a request without the address
        when(restApi.postWithAuthRetry(eq(PROPOSALS), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(400, Recordings.text("pickup-proposals-missing-address.json")));

        // when / then
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro().pickupWindows(List.of(A), warehouse(), LocalDate.of(2026, 10, 8), 4));
        assertInstanceOf(HttpClientException.class, e.getCause());
    }

    @Test
    void pickupWithoutAddressIsNotSupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> allegro().pickupWindows(List.of(A), LocalDate.of(2026, 10, 8), 4));
    }

    @Test
    void orderingSendsTheWindowAsAllegroGaveItAndIsPending() {
        // given
        PickupWindow window = new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0),
                "2026-10-09|14:00|17:00");

        // when
        PickupOrder order = allegro().orderPickup(List.of(A, B), warehouse(), window, "pickup-1");

        // then
        assertEquals(CommandStatus.PENDING, order.status());
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restApi).postWithAuthRetry(eq(COMMANDS), body.capture(), anyMap(), eq(JsonNode.class));
        JsonNode sent = AllegroJson.MAPPER.valueToTree(body.getValue());
        assertEquals("pickup-1", sent.path("commandId").asText());
        assertEquals(2, sent.path("input").path("shipmentIds").size());
        assertEquals("2026-10-09", sent.path("input").path("pickupTime").path("date").asText());
        assertEquals("14:00", sent.path("input").path("pickupTime").path("minTime").asText());
        assertEquals("17:00", sent.path("input").path("pickupTime").path("maxTime").asText());
        assertEquals("Wrocław", sent.path("input").path("address").path("city").asText());
    }

    @Test
    void refusedPickupIsAShippingException() {
        // given
        when(restApi.postWithAuthRetry(eq(COMMANDS), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(422, "{\"errors\":[{\"userMessage\":\"Termin niedostępny\"}]}"));
        PickupWindow window = new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0), null);

        // when / then
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro().orderPickup(List.of(A), warehouse(), window, "pickup-1"));
        assertEquals("Termin niedostępny", e.getMessage());
    }

    @Test
    void pickupWithServerErrorStaysPending() {
        // given
        when(restApi.postWithAuthRetry(eq(COMMANDS), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(503, ""));
        PickupWindow window = new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0), null);

        // when / then
        assertEquals(CommandStatus.PENDING, allegro().orderPickup(List.of(A), warehouse(), window, "pickup-1").status());
    }

    @Test
    void requestTimeoutOnThePickupCommandStaysPending() {
        // given
        when(restApi.postWithAuthRetry(eq(COMMANDS), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(408, ""));
        PickupWindow window = new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0), null);

        // when / then
        assertEquals(CommandStatus.PENDING, allegro().orderPickup(List.of(A), warehouse(), window, "pickup-1").status());
    }

    @Test
    void duplicatePickupCommandStaysPendingInsteadOfBeingRefused() {
        // given: the same command id was accepted before (a retry of an answer we lost)
        when(restApi.postWithAuthRetry(eq(COMMANDS), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(409, ""));
        PickupWindow window = new PickupWindow(LocalDate.of(2026, 10, 9), LocalTime.of(14, 0), LocalTime.of(17, 0), null);

        // when / then
        assertEquals(CommandStatus.PENDING, allegro().orderPickup(List.of(A), warehouse(), window, "pickup-1").status());
    }

    @Test
    void successfulPickupCommandUsesTheCarriersPickupNumberForAllShipments() {
        // given
        when(restApi.fetchWithAuthRetry(eq(COMMANDS + "/pickup-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-command-success.json"));

        // when
        PickupOrder order = allegro().checkPickupOrder("pickup-1");

        // then
        assertEquals(CommandStatus.SUCCEEDED, order.status());
        assertEquals("20261009800071", order.pickupId());
        assertTrue(order.externalIds().isEmpty());
    }

    @Test
    void failedPickupCommandStatesTheReason() {
        // given
        when(restApi.fetchWithAuthRetry(eq(COMMANDS + "/pickup-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-command-error.json"));

        // when
        PickupOrder order = allegro().checkPickupOrder("pickup-1");

        // then
        assertEquals(CommandStatus.FAILED, order.status());
        assertEquals("Przewoźnik nie przyjmie odbioru w wybranym terminie", order.error());
    }

    @Test
    void unknownPickupCommandIsPending() {
        // given
        when(restApi.fetchWithAuthRetry(eq(COMMANDS + "/pickup-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(404, ""));

        // when / then
        assertEquals(CommandStatus.PENDING, allegro().checkPickupOrder("pickup-1").status());
    }
}
