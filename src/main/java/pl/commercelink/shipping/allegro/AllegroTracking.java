package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import pl.commercelink.shipping.api.ShippingWebhookResult.ShipmentState;
import pl.commercelink.shipping.api.TrackingEvent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * GET /order/carriers/{carrierId}/tracking as tracking events in the states the application already handles for
 * webhooks (COLLECTED, DELIVERED). Statuses with no action in CommerceLink (PENDING, ISSUE, RETURNED) are left out.
 */
final class AllegroTracking {

    private AllegroTracking() {
    }

    static List<TrackingEvent> events(JsonNode answer, String waybill) {
        List<TrackingEvent> events = new ArrayList<>();
        for (JsonNode entry : answer.path("waybills")) {
            if (!waybill.equals(AllegroJson.text(entry, "waybill"))) {
                continue;
            }
            // null details: the carrier has nothing yet (the sandbox answers this for every waybill)
            for (JsonNode status : entry.path("trackingDetails").path("statuses")) {
                String code = AllegroJson.text(status, "code");
                String occurredAt = AllegroJson.text(status, "occurredAt");
                ShipmentState state = state(code);
                if (state != null && occurredAt != null) {
                    events.add(new TrackingEvent(state.name(), code, OffsetDateTime.parse(occurredAt)));
                }
            }
        }
        events.sort(Comparator.comparing(TrackingEvent::datetime));
        return events;
    }

    private static ShipmentState state(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "IN_TRANSIT", "RELEASED_FOR_DELIVERY", "AVAILABLE_FOR_PICKUP", "NOTICE_LEFT" -> ShipmentState.COLLECTED;
            case "DELIVERED" -> ShipmentState.DELIVERED;
            default -> null;
        };
    }
}
