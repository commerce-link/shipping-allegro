package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.ParcelTrackingRequest;
import pl.commercelink.shipping.api.TrackingEvent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllegroTrackingTest {

    private static final String SHIPMENT = "9a1b2c3d-1111-4e5f-8a9b-0c1d2e3f4a5b";

    @Mock
    private RestApiWithRetry restApi;

    private AllegroShipping allegro() {
        return new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6);
    }

    private void shipmentAndTracking(String tracking) {
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + SHIPMENT), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-dpd.json"));
        when(restApi.fetchWithAuthRetry(eq("/order/carriers/DPD/tracking"), eq(Map.of("waybill", "0000889416460Q")), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json(tracking));
    }

    @Test
    void allegroTracksOwnShipmentsByPolling() {
        assertTrue(allegro().supportsParcelTracking());
        assertTrue(allegro().supportsTrackingPolling());
    }

    @Test
    void inTransitIsCollectedAndPendingIsSkipped() {
        // given
        shipmentAndTracking("tracking-in-transit.json");

        // when
        List<TrackingEvent> events = allegro().getTrackingEvents(SHIPMENT);

        // then
        assertEquals(List.of(new TrackingEvent("COLLECTED", "IN_TRANSIT", OffsetDateTime.parse("2026-10-09T15:02:00Z"))), events);
    }

    @Test
    void deliveredParcelEndsWithDeliveredSortedByTime() {
        // given
        shipmentAndTracking("tracking-delivered.json");

        // when
        List<TrackingEvent> events = allegro().getTrackingEvents(SHIPMENT);

        // then
        assertEquals(List.of("COLLECTED", "COLLECTED", "DELIVERED"), events.stream().map(TrackingEvent::state).toList());
        assertEquals("DELIVERED", events.get(2).status());
    }

    @Test
    void noTrackingDetailsIsNoEventsNotAnError() {
        // given: what the sandbox answers for every waybill (2026-10-06)
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + SHIPMENT), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-dpd.json"));
        when(restApi.fetchWithAuthRetry(eq("/order/carriers/DPD/tracking"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("tracking-no-details.json"));

        // when / then
        assertTrue(allegro().getTrackingEvents(SHIPMENT).isEmpty());
    }

    @Test
    void foreignWaybillsAreNotTrackedByAllegro() {
        assertThrows(UnsupportedOperationException.class,
                () -> allegro().trackParcel(new ParcelTrackingRequest("123", "dpd", "CommerceLink order 1")));
    }
}
