package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.commercelink.rest.client.BinaryResponse;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.*;

import java.time.LocalDate;
import java.util.ArrayList;
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

    static final String ONE_PACKAGE = "Wysyłam z Allegro: jedna paczka na przesyłkę — każdy karton nadaj osobno.";

    private static final String CREATE_COMMANDS = "/shipment-management/shipments/create-commands";
    private static final String SHIPMENTS = "/shipment-management/shipments/";

    /**
     * Starts creating the shipment under the caller's command id. Allegro's shipment id is known only once the command
     * succeeds, so the answer is PENDING without externalId. Everything checked before the command (order, one
     * package, limits, the method) is a refusal; after the command a 4xx is a refusal and anything else an unknown
     * outcome left to {@link #checkShipmentCreation}.
     */
    @Override
    public ShipmentCreation createShipment(ShipmentRequest request, String commandId) {
        if (request.orderReference() == null || request.orderReference().externalOrderId() == null) {
            throw new ShippingException("Wysyłam z Allegro nadaje tylko zamówienia z Allegro: brak numeru zamówienia Allegro.");
        }
        if (request.parcels().size() != 1) {
            throw new ShippingException(ONE_PACKAGE);
        }
        ObjectNode input = AllegroShipmentInput.of(proposalForCreation(request), request, labelFormat);
        ObjectNode command = AllegroJson.object();
        command.put("commandId", commandId);
        command.set("input", input);
        try {
            api.post(CREATE_COMMANDS, command);
        } catch (HttpClientException ex) {
            // the same command id was accepted before (a retry of an answer we lost); refusing would invite a second paid shipment
            if (AllegroErrors.isDuplicateCommand(ex)) {
                log.info("Allegro create command {} already exists", commandId);
                return ShipmentCreation.pending(commandId, null);
            }
            if (AllegroErrors.isClientError(ex)) {
                throw AllegroErrors.exception(ex);
            }
            log.warn("Allegro create command {} has an unknown outcome: HTTP {}", commandId, ex.getStatusCode());
        } catch (RuntimeException ex) {
            log.warn("Allegro create command {} has an unknown outcome: {}", commandId, ex.getMessage());
        }
        return ShipmentCreation.pending(commandId, null);
    }

    /** Nothing is sent before this read, so every failure of it is a refusal the operator may simply retry. */
    private JsonNode proposalForCreation(ShipmentRequest request) {
        String orderId = request.orderReference().externalOrderId();
        try {
            return api.get(PROPOSALS + orderId);
        } catch (HttpClientException ex) {
            if (AllegroErrors.hasCode(ex, "UNSUPPORTED_DELIVERY_METHOD")) {
                throw new ShippingException(UNSUPPORTED_METHOD, ex, List.of(UNSUPPORTED_METHOD));
            }
            if (AllegroErrors.isClientError(ex)) {
                throw AllegroErrors.exception(ex);
            }
            log.warn("Allegro did not answer the delivery proposal of order {}: HTTP {}", orderId, ex.getStatusCode());
            throw new ShippingException("Allegro nie odpowiedziało przed nadaniem (HTTP " + ex.getStatusCode()
                    + ") — nic nie zostało nadane, spróbuj ponownie.");
        } catch (RuntimeException ex) {
            log.warn("Allegro did not answer the delivery proposal of order {}", orderId, ex);
            throw new ShippingException("Allegro nie odpowiedziało przed nadaniem — nic nie zostało nadane, spróbuj ponownie.");
        }
    }

    @Override
    public ShipmentCreation checkShipmentCreation(String commandId, String externalId) {
        JsonNode status;
        try {
            status = api.get(CREATE_COMMANDS + "/" + commandId);
        } catch (HttpClientException ex) {
            if (ex.getStatusCode() == 404) {
                // a command sent without an answer may still be saved: failing it would invite a second paid shipment
                log.warn("Allegro create command {} is not known yet; still pending", commandId);
                return ShipmentCreation.pending(commandId, externalId);
            }
            throw AllegroErrors.exception(ex);
        }
        return switch (String.valueOf(AllegroJson.text(status, "status"))) {
            case "SUCCESS" -> created(commandId, AllegroJson.text(status, "shipmentId"));
            case "ERROR" -> ShipmentCreation.failed(commandId, externalId,
                    AllegroErrors.joined(status, "Allegro nie utworzyło przesyłki."));
            default -> ShipmentCreation.pending(commandId, externalId);
        };
    }

    /** The shipment of a successful command; its waybill may come a moment later, which is still PENDING. */
    private ShipmentCreation created(String commandId, String shipmentId) {
        if (shipmentId == null) {
            return ShipmentCreation.pending(commandId, null);
        }
        JsonNode shipment;
        try {
            shipment = api.get(SHIPMENTS + shipmentId);
        } catch (HttpClientException ex) {
            // the command succeeded, so the shipment exists and is paid: unreadable details must not look like a failure
            if (ex.getStatusCode() == 404 || ex.getStatusCode() >= 500) {
                log.warn("Allegro shipment {} is not readable yet: HTTP {}", shipmentId, ex.getStatusCode());
                return ShipmentCreation.pending(commandId, shipmentId);
            }
            throw AllegroErrors.exception(ex);
        } catch (RuntimeException ex) {
            log.warn("Allegro shipment {} is not readable yet: {}", shipmentId, ex.getMessage());
            return ShipmentCreation.pending(commandId, shipmentId);
        }
        String carrier = courierOf(shipment);
        boolean pickupAvailable = shipment.path("pickupAvailable").asBoolean(false);
        List<ShipmentResult.ShipmentParcelResult> parcels = new ArrayList<>();
        for (JsonNode pkg : shipment.path("packages")) {
            String waybill = AllegroJson.text(pkg, "waybill");
            if (waybill == null) {
                return ShipmentCreation.pending(commandId, shipmentId);
            }
            // One by Allegro shipments cannot be cancelled: Allegro's rule
            parcels.add(new ShipmentResult.ShipmentParcelResult(waybill, carrier, null, pickupAvailable, null,
                    carrier == null || !carrier.startsWith("ALLEGRO")));
        }
        if (parcels.isEmpty()) {
            return ShipmentCreation.pending(commandId, shipmentId);
        }
        return ShipmentCreation.succeeded(commandId, new ShipmentResult(shipmentId, parcels, null));
    }

    /**
     * The courier that carries the shipment. Allegro answers carrier ALLEGRO for every Allegro Delivery shipment and
     * names the courier in transport (DPD, DHL, ALLEGRO_ONE_KURIER); the application groups pickups by this value, so
     * ALLEGRO alone would put shipments of different couriers into one pickup.
     */
    private static String courierOf(JsonNode shipment) {
        String transport = shipment.path("transport").path(0).asText(null);
        return transport != null && !transport.isBlank() ? transport : AllegroJson.text(shipment, "carrier");
    }

    private static final String CANCEL_COMMANDS = "/shipment-management/shipments/cancel-commands";

    /** Allegro refuses to cancel One by Allegro shipments and anything already picked up: those come back FAILED. */
    @Override
    public ShipmentCancellation cancelShipment(String externalId, String commandId) {
        ObjectNode command = AllegroJson.object();
        command.put("commandId", commandId);
        command.putObject("input").put("shipmentId", externalId);
        try {
            api.post(CANCEL_COMMANDS, command);
        } catch (HttpClientException ex) {
            // the same command id was accepted before (a retry of an answer we lost); refusing would invite a duplicate cancellation
            if (AllegroErrors.isDuplicateCommand(ex)) {
                log.info("Allegro cancel command {} already exists", commandId);
                return ShipmentCancellation.pending(commandId);
            }
            if (AllegroErrors.isClientError(ex)) {
                throw AllegroErrors.exception(ex);
            }
            log.warn("Allegro cancel command {} has an unknown outcome: HTTP {}", commandId, ex.getStatusCode());
        } catch (RuntimeException ex) {
            log.warn("Allegro cancel command {} has an unknown outcome: {}", commandId, ex.getMessage());
        }
        return ShipmentCancellation.pending(commandId);
    }

    @Override
    public ShipmentCancellation checkShipmentCancellation(String commandId, String externalId) {
        JsonNode status;
        try {
            status = api.get(CANCEL_COMMANDS + "/" + commandId);
        } catch (HttpClientException ex) {
            if (ex.getStatusCode() == 404) {
                log.warn("Allegro cancel command {} is not known yet; still pending", commandId);
                return ShipmentCancellation.pending(commandId);
            }
            throw AllegroErrors.exception(ex);
        }
        return switch (String.valueOf(AllegroJson.text(status, "status"))) {
            case "SUCCESS" -> ShipmentCancellation.succeeded(commandId, List.of());
            case "ERROR" -> ShipmentCancellation.failed(commandId,
                    AllegroErrors.joined(status, "Allegro nie anulowało przesyłki."), List.of());
            default -> ShipmentCancellation.pending(commandId);
        };
    }

    private static final String LABEL = "/shipment-management/label";

    @Override
    public boolean supportsLabels() {
        return true;
    }

    /**
     * The file format (PDF or ZPL) was fixed when the shipment was created, so it is read from the shipment; the page
     * size comes from the current settings (Allegro ignores it for ZPL).
     */
    @Override
    public Label getLabel(String externalId) {
        JsonNode shipment = read(SHIPMENTS + externalId);
        boolean zpl = "ZPL".equals(AllegroJson.text(shipment, "labelFormat"));
        ObjectNode body = AllegroJson.object();
        body.putArray("shipmentIds").add(externalId);
        body.put("pageSize", labelFormat.pageSize());
        body.put("cutLine", false);
        BinaryResponse response;
        try {
            response = api.postForBytes(LABEL, body, "application/octet-stream");
        } catch (RuntimeException ex) {
            throw AllegroErrors.failure(ex);
        }
        if (response == null || response.content() == null || response.content().length == 0) {
            throw new ShippingException("Allegro nie ma jeszcze etykiety przesyłki " + externalId + ".");
        }
        String waybill = AllegroJson.text(shipment.path("packages").path(0), "waybill");
        String name = "etykieta-" + (waybill == null ? externalId : waybill) + (zpl ? ".zpl" : ".pdf");
        return new Label(response.content(), zpl ? "text/plain" : "application/pdf", name);
    }

    private static final String PICKUP_PROPOSALS = "/shipment-management/pickup-proposals";
    private static final String PICKUP_COMMANDS = "/shipment-management/pickups/create-commands";

    @Override
    public boolean supportsPickups() {
        return true;
    }

    /** Allegro needs the pickup address; the variants without it stay unsupported (the SPI default). */
    @Override
    public List<PickupWindow> pickupWindows(List<String> externalIds, ShipmentAddress pickup, LocalDate readyDate,
                                            int daysAhead) {
        ObjectNode body = AllegroJson.object();
        externalIds.forEach(body.putArray("shipmentIds")::add);
        body.put("readyDate", readyDate.toString());
        body.set("address", AllegroShipmentInput.address(pickup));
        JsonNode answer;
        try {
            answer = api.post(PICKUP_PROPOSALS, body);
        } catch (RuntimeException ex) {
            throw AllegroErrors.failure(ex);
        }
        return AllegroPickups.commonWindows(answer, externalIds, readyDate.plusDays(daysAhead));
    }

    @Override
    public PickupOrder orderPickup(List<String> externalIds, ShipmentAddress pickup, PickupWindow window, String commandId) {
        String[] times = AllegroPickups.times(window);
        ObjectNode command = AllegroJson.object();
        command.put("commandId", commandId);
        ObjectNode input = command.putObject("input");
        externalIds.forEach(input.putArray("shipmentIds")::add);
        ObjectNode pickupTime = input.putObject("pickupTime");
        pickupTime.put("date", times[0]);
        pickupTime.put("minTime", times[1]);
        pickupTime.put("maxTime", times[2]);
        input.set("address", AllegroShipmentInput.address(pickup));
        try {
            api.post(PICKUP_COMMANDS, command);
        } catch (HttpClientException ex) {
            // the same command id was accepted before (a retry of an answer we lost); refusing would invite a second pickup
            if (AllegroErrors.isDuplicateCommand(ex)) {
                log.info("Allegro pickup command {} already exists", commandId);
                return PickupOrder.pending(commandId);
            }
            if (AllegroErrors.isClientError(ex)) {
                throw AllegroErrors.exception(ex);
            }
            log.warn("Allegro pickup command {} has an unknown outcome: HTTP {}", commandId, ex.getStatusCode());
        } catch (RuntimeException ex) {
            log.warn("Allegro pickup command {} has an unknown outcome: {}", commandId, ex.getMessage());
        }
        return PickupOrder.pending(commandId);
    }

    /** A successful pickup covers every shipment of the command (empty list); the carrier's number is shown. */
    @Override
    public PickupOrder checkPickupOrder(String commandId) {
        JsonNode status;
        try {
            status = api.get(PICKUP_COMMANDS + "/" + commandId);
        } catch (HttpClientException ex) {
            if (ex.getStatusCode() == 404) {
                log.warn("Allegro pickup command {} is not known yet; still pending", commandId);
                return PickupOrder.pending(commandId);
            }
            throw AllegroErrors.exception(ex);
        }
        return switch (String.valueOf(AllegroJson.text(status, "status"))) {
            case "SUCCESS" -> {
                String carrierPickupId = AllegroJson.text(status, "carrierPickupId");
                yield PickupOrder.succeeded(commandId,
                        carrierPickupId != null ? carrierPickupId : AllegroJson.text(status, "pickupId"), List.of());
            }
            case "ERROR" -> PickupOrder.failed(commandId, AllegroErrors.joined(status, "Allegro nie zamówiło odbioru."));
            default -> PickupOrder.pending(commandId);
        };
    }

    @Override
    public boolean supportsParcelTracking() {
        return true;
    }

    /** No webhooks in Allegro: the application polls {@link #getTrackingEvents}. Foreign waybills are not tracked. */
    @Override
    public boolean supportsTrackingPolling() {
        return true;
    }

    @Override
    public List<TrackingEvent> getTrackingEvents(String externalId) {
        JsonNode shipment = read(SHIPMENTS + externalId);
        String carrier = AllegroJson.text(shipment, "carrier");
        String waybill = AllegroJson.text(shipment.path("packages").path(0), "waybill");
        if (carrier == null || waybill == null) {
            return List.of();
        }
        JsonNode answer;
        try {
            answer = api.get("/order/carriers/" + carrier + "/tracking", Map.of("waybill", waybill));
        } catch (RuntimeException ex) {
            throw AllegroErrors.failure(ex);
        }
        return AllegroTracking.events(answer, waybill);
    }
}
