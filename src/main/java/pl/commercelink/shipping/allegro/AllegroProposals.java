package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import pl.commercelink.shipping.api.DeliveryPoint;
import pl.commercelink.shipping.api.DeliveryType;
import pl.commercelink.shipping.api.PackageOption;
import pl.commercelink.shipping.api.ShipmentProposal;

import java.util.ArrayList;
import java.util.List;

/** GET /shipment-management/delivery-proposals/{orderId} (+ the checkout form) as a ShipmentProposal. */
final class AllegroProposals {

    static final String NO_OPTIONS = "Allegro nie podało opcji nadania dla tego zamówienia.";

    private AllegroProposals() {
    }

    /** The option the adapter ships with: the first parcel ("PACKAGE") option, else the first one. */
    static JsonNode primaryOption(JsonNode proposal) {
        JsonNode first = MissingNode.getInstance();
        for (JsonNode option : proposal.path("deliveryOptions")) {
            if ("PACKAGE".equals(AllegroJson.text(option, "packageType"))) {
                return option;
            }
            if (first.isMissingNode()) {
                first = option;
            }
        }
        return first;
    }

    static ShipmentProposal toProposal(JsonNode proposal, JsonNode checkoutForm, String carrierId) {
        JsonNode option = primaryOption(proposal);
        if (option.isMissingNode()) {
            return ShipmentProposal.unavailable(NO_OPTIONS);
        }
        JsonNode delivery = checkoutForm.path("delivery");
        String point = AllegroJson.text(proposal.path("suggestedInput").path("receiver"), "point");
        if (point == null) {
            point = AllegroJson.text(delivery.path("pickupPoint"), "id");
        }
        List<PackageOption> options = new ArrayList<>();
        proposal.path("deliveryOptions").forEach(o -> options.add(packageOption(o)));
        JsonNode limits = option.path("limits");
        return ShipmentProposal.available(
                AllegroJson.text(delivery.path("method"), "name"),
                carrierId,
                point == null ? null : new DeliveryPoint(point),
                deliveryType(option),
                options,
                AllegroJson.amount(limits.path("cashOnDelivery")),
                AllegroJson.amount(limits.path("insurance")));
    }

    static PackageOption packageOption(JsonNode option) {
        JsonNode limits = option.path("limits");
        JsonNode dimensions = limits.path("dimensions");
        return new PackageOption(
                AllegroJson.text(option, "packageType"),
                AllegroJson.measure(dimensions.path("length")),
                AllegroJson.measure(dimensions.path("width")),
                AllegroJson.measure(dimensions.path("height")),
                AllegroJson.measure(limits.path("weight")));
    }

    static DeliveryType deliveryType(JsonNode option) {
        String type = AllegroJson.text(option, "deliveryType");
        if ("APM".equals(type)) {
            return DeliveryType.LOCKER;
        }
        if ("PUDO".equals(type)) {
            return DeliveryType.POINT;
        }
        return DeliveryType.DOOR;
    }
}
