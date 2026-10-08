package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.*;
import pl.commercelink.shipping.api.testing.ShippingProviderContractTest;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AllegroContractTest extends ShippingProviderContractTest {

    private static final String ORDER = "3daafd00-c31d-11f1-ac0d-1b8fc439c8a2";
    private static final String SHIPMENT = "b7c6b01a-2916-4ae7-ae3d-570140674e25";
    private static final String CREATE = "/shipment-management/shipments/create-commands";

    @Mock
    private RestApiWithRetry restApi;

    @BeforeEach
    void stubAllegro() {
        answer("/shipment-management/delivery-proposals/" + ORDER, Recordings.json("delivery-proposal-dpd.json"));
        answer("/order/checkout-forms/" + ORDER, Recordings.json("checkout-form-allegro-dpd.json"));
        answer("/shipment-management/delivery-services", Recordings.json("delivery-services.json"));
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-proposals/own-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(400, Recordings.text("delivery-proposal-unsupported.json")));
        when(restApi.fetchWithAuthRetry(startsWith(CREATE + "/"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-in-progress.json"));
        answer("/shipment-management/shipments/" + SHIPMENT, Recordings.json("shipment-dpd.json"));
        when(restApi.postWithAuthRetry(eq("/shipment-management/pickup-proposals"), any(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("pickup-proposals.json"));
        when(restApi.fetchWithAuthRetry(eq("/order/carriers/ALLEGRO/tracking"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("tracking-in-transit.json"));
    }

    private void answer(String path, JsonNode answer) {
        when(restApi.fetchWithAuthRetry(eq(path), anyMap(), anyMap(), eq(JsonNode.class))).thenReturn(answer);
    }

    @Override
    protected ShippingProvider provider() {
        return new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6);
    }

    @Override
    protected ShipmentRequest sampleRequest() {
        ShipmentAddress shop = new ShipmentAddress("Magazyn", null, "Magazynowa 3", "50-001", "Wrocław", "PL",
                "m@sklep.pl", "600700800");
        return ShipmentRequest.builder().pickup(shop).sender(shop).receiver(shop)
                .parcels(List.of(new Parcel(20, 30, 10, 2, 100, "test", "package")))
                .orderReference(sampleOrderReference()).build();
    }

    @Override
    protected void completeCreation(String commandId) {
        answer(CREATE + "/" + commandId, AllegroJson.MAPPER.valueToTree(Map.of(
                "commandId", commandId, "status", "SUCCESS", "shipmentId", SHIPMENT)));
    }

    @Override
    protected ShipmentCreation failCreation(String commandId) {
        answer(CREATE + "/" + commandId, Recordings.json("create-command-error-method.json"));
        return provider().checkShipmentCreation(commandId, null);
    }

    @Override
    protected OrderReference sampleOrderReference() {
        return new OrderReference("Allegro", ORDER, "CL-1001");
    }

    @Override
    protected OrderReference unsupportedOrderReference() {
        return new OrderReference("Allegro", "own-1", "CL-1003");
    }

    @Override
    protected ShipmentAddress samplePickupAddress() {
        return new ShipmentAddress("Magazyn", null, "Magazynowa 3", "50-001", "Wrocław", "PL", "m@sklep.pl", "600700800");
    }

    @Override
    protected String sampleTrackedExternalId() {
        return SHIPMENT;
    }
}
