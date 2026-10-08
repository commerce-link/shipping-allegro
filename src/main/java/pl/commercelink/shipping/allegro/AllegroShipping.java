package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Wysyłam z Allegro" (shipment-management API). Commands (create, cancel, pickup) are Allegro commands under the
 * caller's command id, so a repeated request with the same id never creates a second shipment.
 */
public class AllegroShipping implements ShippingProvider {

    private static final Logger log = LoggerFactory.getLogger(AllegroShipping.class);

    private final AllegroApi api;
    private final AllegroLabelFormat labelFormat;

    public AllegroShipping(RestApiWithRetry restApi, AllegroLabelFormat labelFormat) {
        this.api = new AllegroApi(restApi);
        this.labelFormat = labelFormat;
    }

    AllegroLabelFormat labelFormat() {
        return labelFormat;
    }

    private static final String DELIVERY_SERVICES = "/shipment-management/delivery-services";

    /**
     * Carriers of the Polish Allegro Delivery services. The application calls it to check the connection and the
     * shipments consent (403 → {@link AllegroErrors#NO_SHIPMENTS_CONSENT}); the list itself is not stored.
     */
    @Override
    public List<Carrier> getAvailableCarriers() {
        JsonNode services = read(DELIVERY_SERVICES);
        Map<String, Carrier> byCarrier = new LinkedHashMap<>();
        for (JsonNode service : services.path("services")) {
            String carrierId = AllegroJson.text(service, "carrierId");
            if (carrierId == null || !isPolish(service)) {
                continue;
            }
            byCarrier.putIfAbsent(carrierId, new Carrier(carrierId, carrierId, carrierId));
        }
        return List.copyOf(byCarrier.values());
    }

    private static boolean isPolish(JsonNode service) {
        JsonNode marketplaces = service.path("marketplaces");
        if (!marketplaces.isArray() || marketplaces.isEmpty()) {
            return true;
        }
        for (JsonNode marketplace : marketplaces) {
            if ("allegro-pl".equals(marketplace.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode read(String path) {
        try {
            return api.get(path);
        } catch (RuntimeException ex) {
            throw AllegroErrors.failure(ex);
        }
    }

    static final String UNSUPPORTED_METHOD =
            "Metoda dostawy z Twojej umowy z przewoźnikiem — Wysyłam z Allegro jej nie nadaje.";

    static final String PROPOSALS = "/shipment-management/delivery-proposals/";
    private static final String CHECKOUT_FORMS = "/order/checkout-forms/";

    @Override
    public boolean supportsShipmentProposals() {
        return true;
    }

    /**
     * The order's Allegro delivery method, point and limits for the shipping form. An order on the seller's own carrier
     * contract cannot go through Wysyłam z Allegro: that is an unavailable proposal, not an error.
     */
    @Override
    public ShipmentProposal proposeShipment(OrderReference reference) {
        JsonNode proposal;
        try {
            proposal = api.get(PROPOSALS + reference.externalOrderId());
        } catch (HttpClientException ex) {
            if (AllegroErrors.hasCode(ex, "UNSUPPORTED_DELIVERY_METHOD")) {
                return ShipmentProposal.unavailable(UNSUPPORTED_METHOD);
            }
            throw AllegroErrors.exception(ex);
        }
        JsonNode checkoutForm = read(CHECKOUT_FORMS + reference.externalOrderId());
        String methodId = AllegroJson.text(proposal.path("suggestedInput"), "deliveryMethodId");
        return AllegroProposals.toProposal(proposal, checkoutForm, carrierOf(methodId));
    }

    /** The carrier of a delivery method, from the delivery services list; only shown, so a failure leaves it empty. */
    private String carrierOf(String deliveryMethodId) {
        if (deliveryMethodId == null) {
            return null;
        }
        try {
            for (JsonNode service : api.get(DELIVERY_SERVICES).path("services")) {
                if (deliveryMethodId.equals(AllegroJson.text(service.path("id"), "deliveryMethodId"))) {
                    return AllegroJson.text(service, "carrierId");
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not read the carrier of Allegro delivery method {}: {}", deliveryMethodId, ex.getMessage());
        }
        return null;
    }

    /** The carrier and the price follow from the order and the Allegro price list: nothing to estimate. */
    @Override
    public List<ShippingEstimate> estimateShipment(ShipmentRequest request, Set<String> carrierIds) {
        return List.of();
    }

    @Override
    public ShipmentCreation createShipment(ShipmentRequest request, String commandId) {
        throw new UnsupportedOperationException("Implemented in Task 4");
    }

    @Override
    public ShipmentCreation checkShipmentCreation(String commandId, String externalId) {
        throw new UnsupportedOperationException("Implemented in Task 4");
    }

    @Override
    public ShipmentCancellation cancelShipment(String externalId, String commandId) {
        throw new UnsupportedOperationException("Implemented in Task 4");
    }
}
