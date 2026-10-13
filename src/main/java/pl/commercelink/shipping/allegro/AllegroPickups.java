package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import pl.commercelink.shipping.api.PickupWindow;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/** Courier pickup windows from POST /shipment-management/pickup-proposals. */
final class AllegroPickups {

    private AllegroPickups() {
    }

    /**
     * Windows offered for every one of the shipments (one courier comes for all of them), from the ready date up to the
     * last day, sorted. The token keeps Allegro's own texts, so the window is sent back exactly as it was offered.
     */
    static List<PickupWindow> commonWindows(JsonNode answer, List<String> shipmentIds, LocalDate lastDay) {
        Map<String, Map<String, PickupWindow>> byShipment = new HashMap<>();
        for (JsonNode proposal : proposals(answer)) {
            String shipmentId = AllegroJson.text(proposal, "shipmentId");
            if (shipmentId == null) {
                continue;
            }
            Map<String, PickupWindow> windows = byShipment.computeIfAbsent(shipmentId, id -> new LinkedHashMap<>());
            for (JsonNode time : proposal.path("pickupTimes")) {
                String date = AllegroJson.text(time, "date");
                String from = AllegroJson.text(time, "minTime");
                String to = AllegroJson.text(time, "maxTime");
                if (date == null || from == null || to == null) {
                    continue;
                }
                String token = date + "|" + from + "|" + to;
                windows.put(token, new PickupWindow(LocalDate.parse(date), LocalTime.parse(from), LocalTime.parse(to), token));
            }
        }
        Map<String, PickupWindow> common = null;
        for (String shipmentId : shipmentIds) {
            Map<String, PickupWindow> windows = byShipment.getOrDefault(shipmentId, Map.of());
            if (common == null) {
                common = new LinkedHashMap<>(windows);
            } else {
                common.keySet().retainAll(windows.keySet());
            }
        }
        if (common == null) {
            return List.of();
        }
        return common.values().stream()
                .filter(w -> !w.date().isAfter(lastDay))
                .sorted()
                .toList();
    }

    /** Allegro answers a list of {proposals: [...], address}; a single object is read the same way. */
    private static List<JsonNode> proposals(JsonNode answer) {
        List<JsonNode> proposals = new ArrayList<>();
        Iterable<JsonNode> groups = answer.isArray() ? answer : List.of(answer);
        for (JsonNode group : groups) {
            group.path("proposals").forEach(proposals::add);
        }
        return proposals;
    }

    /** The texts Allegro gave for the window (kept in the token), else the window's own times as HH:mm. */
    static String[] times(PickupWindow window) {
        if (window.token() != null) {
            String[] parts = window.token().split("\\|");
            if (parts.length == 3) {
                return parts;
            }
        }
        return new String[]{window.date().toString(), hhmm(window.from()), hhmm(window.to())};
    }

    private static String hhmm(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
