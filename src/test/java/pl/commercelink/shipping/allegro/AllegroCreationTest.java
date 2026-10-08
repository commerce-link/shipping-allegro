package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.*;

import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllegroCreationTest {

    private static final String ORDER = "3daafd00-c31d-11f1-ac0d-1b8fc439c8a2";
    private static final String SHIPMENT = "b7c6b01a-2916-4ae7-ae3d-570140674e25";
    private static final String ONE = "dde12f96-34d6-4513-8321-2d47a6b5251e";
    private static final String CREATE = "/shipment-management/shipments/create-commands";

    @Mock
    private RestApiWithRetry restApi;

    private AllegroShipping allegro(AllegroLabelFormat format) {
        return new AllegroShipping(restApi, format);
    }

    private static ShipmentAddress shop() {
        return new ShipmentAddress("Magazyn Sklepu", "Sklep sp. z o.o.", "Magazynowa 3", "50-001", "Wrocław", "PL",
                "magazyn@sklep.pl", "600700800");
    }

    private static ShipmentRequest.Builder request(Parcel... parcels) {
        ShipmentAddress fromOrder = new ShipmentAddress("Ktoś Inny", null, "Zła 1", "00-001", "Warszawa", "PL",
                "jan@gmail.com", "111222333");
        return ShipmentRequest.builder().pickup(shop()).sender(shop()).receiver(fromOrder)
                .parcels(List.of(parcels))
                .orderReference(new OrderReference("Allegro", ORDER, "CL-1001"));
    }

    private static Parcel parcel(int insurance) {
        return new Parcel(20, 30, 10, 2, insurance, "Zamówienie CL-1001", "package");
    }

    private void proposal(String recording) {
        when(restApi.fetchWithAuthRetry(eq(AllegroShipping.PROPOSALS + ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json(recording));
    }

    private JsonNode sentInput() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restApi).postWithAuthRetry(eq(CREATE), body.capture(), anyMap(), eq(JsonNode.class));
        return AllegroJson.MAPPER.valueToTree(body.getValue());
    }

    @Test
    void createSendsACommandUnderTheCallersIdAndAnswersPendingWithoutShipmentId() {
        // given
        proposal("delivery-proposal-dpd.json");

        // when
        ShipmentCreation started = allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(300)).build(), "cmd-1");

        // then
        assertEquals(CommandStatus.PENDING, started.status());
        assertEquals("cmd-1", started.commandId());
        assertNull(started.externalId());
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restApi).postWithAuthRetry(eq(CREATE), body.capture(), anyMap(), eq(JsonNode.class));
        assertEquals("cmd-1", AllegroJson.MAPPER.valueToTree(body.getValue()).path("commandId").asText());
    }

    @Test
    void receiverAndMethodComeFromAllegroNeverFromTheRequest() {
        // given
        proposal("delivery-proposal-dpd.json");

        // when
        allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(300)).build(), "cmd-1");

        // then
        JsonNode input = sentInput().path("input");
        assertEquals("x7k2m9p4qa+1a2b3c4d5@user.allegrogroup.pl", input.path("receiver").path("email").asText());
        assertEquals("Jan Test", input.path("receiver").path("name").asText());
        assertEquals("50-106", input.path("receiver").path("postalCode").asText());
        // Allegro suggests no method id (null) and takes the method from the order: the sandbox created the shipment so
        assertTrue(input.path("deliveryMethodId").isNull());
        assertFalse(input.has("credentialsId"));
    }

    @Test
    void senderPackageLabelFormatAndReferenceAreOverridden() {
        // given
        proposal("delivery-proposal-dpd.json");

        // when
        allegro(AllegroLabelFormat.ZPL).createShipment(request(parcel(300)).build(), "cmd-1");

        // then
        JsonNode input = sentInput().path("input");
        assertEquals("Magazyn Sklepu", input.path("sender").path("name").asText());
        assertEquals("50-001", input.path("sender").path("postalCode").asText());
        JsonNode pkg = input.path("packages").get(0);
        assertEquals("PACKAGE", pkg.path("type").asText());
        assertEquals(30, pkg.path("length").path("value").asInt());
        assertEquals(20, pkg.path("width").path("value").asInt());
        assertEquals(10, pkg.path("height").path("value").asInt());
        assertEquals("CENTIMETER", pkg.path("height").path("unit").asText());
        assertEquals(2, pkg.path("weight").path("value").asInt());
        assertEquals("KILOGRAMS", pkg.path("weight").path("unit").asText());
        assertEquals(1, input.path("packages").size());
        assertEquals("ZPL", input.path("labelFormat").asText());
        assertEquals("CL-1001", input.path("referenceNumber").asText());
        assertEquals("300.00", input.path("insurance").path("amount").asText());
        assertFalse(input.has("cashOnDelivery") && !input.path("cashOnDelivery").isNull());
    }

    @Test
    void insuranceIsRaisedToTheCashOnDeliveryAmount() {
        // given
        proposal("delivery-proposal-dpd.json");
        ShipmentRequest request = request(parcel(100))
                .options(new ShipmentOptions(false, false,
                        new ShipmentOptions.CashOnDelivery(249.9, "PL61109010140000071219812874", "Sklep sp. z o.o.", null)))
                .build();

        // when
        allegro(AllegroLabelFormat.PDF_A6).createShipment(request, "cmd-1");

        // then
        JsonNode input = sentInput().path("input");
        assertEquals("249.90", input.path("cashOnDelivery").path("amount").asText());
        assertEquals("PLN", input.path("cashOnDelivery").path("currency").asText());
        assertEquals("PL61109010140000071219812874", input.path("cashOnDelivery").path("iban").asText());
        assertEquals("Sklep sp. z o.o.", input.path("cashOnDelivery").path("ownerName").asText());
        assertEquals("249.90", input.path("insurance").path("amount").asText());
    }

    @Test
    void moreThanOnePackageIsRefusedBeforeAnythingIsSent() {
        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100), parcel(100)).build(), "cmd-1"));

        // then
        assertEquals(AllegroShipping.ONE_PACKAGE, e.getMessage());
        assertNull(e.getCause());
        verifyNoInteractions(restApi);
    }

    @Test
    void requestWithoutAllegroOrderIsRefused() {
        // given
        ShipmentRequest request = request(parcel(100)).orderReference(null).build();

        // when / then
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request, "cmd-1"));
        assertNull(e.getCause());
        verifyNoInteractions(restApi);
    }

    @Test
    void parcelOverTheMethodsLimitsIsRefusedWithTheLimitNamed() {
        // given: the locker allows 64 x 38 x 41 cm and 25 kg
        when(restApi.fetchWithAuthRetry(eq(AllegroShipping.PROPOSALS + ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("delivery-proposal-locker.json"));
        ShipmentRequest request = request(new Parcel(40, 70, 30, 5, 100, "x", "package")).build();

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request, "cmd-1"));

        // then
        assertTrue(e.getMessage().contains("wymiary"), e.getMessage());
        verify(restApi, never()).postWithAuthRetry(eq(CREATE), any(), anyMap(), any());
    }

    @Test
    void cashOnDeliveryOverTheLimitIsRefused() {
        // given: only the cash-on-delivery option of the DPD proposal, which allows 6000 PLN
        ObjectNode cashOnDeliveryOnly = Recordings.json("delivery-proposal-dpd.json").deepCopy();
        ArrayNode options = (ArrayNode) cashOnDeliveryOnly.path("deliveryOptions");
        options.remove(0);
        assertEquals("POSTPAID", options.get(0).path("paymentType").asText());
        when(restApi.fetchWithAuthRetry(eq(AllegroShipping.PROPOSALS + ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(cashOnDeliveryOnly);
        ShipmentRequest request = request(parcel(100))
                .options(new ShipmentOptions(false, false, new ShipmentOptions.CashOnDelivery(7000, "PL61", "Sklep", null)))
                .build();

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request, "cmd-1"));

        // then
        assertTrue(e.getMessage().contains("Pobranie"), e.getMessage());
    }

    @Test
    void orderOnTheSellersOwnContractIsRefusedWithAllegrosCause() {
        // given
        when(restApi.fetchWithAuthRetry(eq(AllegroShipping.PROPOSALS + ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(400, Recordings.text("delivery-proposal-unsupported.json")));

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1"));

        // then
        assertEquals(AllegroShipping.UNSUPPORTED_METHOD, e.getMessage());
        assertEquals(List.of(AllegroShipping.UNSUPPORTED_METHOD), e.providerMessages());
        assertInstanceOf(HttpClientException.class, e.getCause());
    }

    @Test
    void proposalServerErrorIsARefusalWithoutHttpCauseBecauseNothingWasSent() {
        // given
        when(restApi.fetchWithAuthRetry(eq(AllegroShipping.PROPOSALS + ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(503, "<html>down</html>"));

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1"));

        // then
        assertNull(e.getCause());
    }

    @Test
    void commandRefusedByAllegroThrowsWithTheHttpCauseAndUserMessages() {
        // given
        proposal("delivery-proposal-dpd.json");
        when(restApi.postWithAuthRetry(eq(CREATE), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(422,
                        "{\"errors\":[{\"code\":\"VALIDATION_ERROR\",\"userMessage\":\"Nieprawidłowy kod pocztowy nadawcy\"}]}"));

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1"));

        // then
        assertInstanceOf(HttpClientException.class, e.getCause());
        assertEquals(List.of("Nieprawidłowy kod pocztowy nadawcy"), e.providerMessages());
    }

    @Test
    void commandWithServerErrorHasAnUnknownOutcomeAndStaysPending() {
        // given
        proposal("delivery-proposal-dpd.json");
        when(restApi.postWithAuthRetry(eq(CREATE), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(502, "bad gateway"));

        // when
        ShipmentCreation started = allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1");

        // then
        assertEquals(CommandStatus.PENDING, started.status());
    }

    @Test
    void requestTimeoutOnTheCreateCommandStaysPendingBecauseAllegroMayHaveProcessedIt() {
        // given
        proposal("delivery-proposal-dpd.json");
        when(restApi.postWithAuthRetry(eq(CREATE), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(408, ""));

        // when
        ShipmentCreation started = allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1");

        // then
        assertEquals(CommandStatus.PENDING, started.status());
    }

    @Test
    void commandAlreadyKnownToAllegroStaysPendingInsteadOfBeingRefused() {
        // given
        proposal("delivery-proposal-dpd.json");
        when(restApi.postWithAuthRetry(eq(CREATE), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(409, "{\"errors\":[{\"code\":\"CONFLICT\"}]}"));

        // when
        ShipmentCreation started = allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1");

        // then
        assertEquals(CommandStatus.PENDING, started.status());
        assertEquals("cmd-1", started.commandId());
    }

    @Test
    void commandWithoutAnswerStaysPending() {
        // given
        proposal("delivery-proposal-dpd.json");
        when(restApi.postWithAuthRetry(eq(CREATE), any(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new RuntimeException(new HttpTimeoutException("request timed out")));

        // when
        ShipmentCreation started = allegro(AllegroLabelFormat.PDF_A6).createShipment(request(parcel(100)).build(), "cmd-1");

        // then
        assertEquals(CommandStatus.PENDING, started.status());
    }

    @Test
    void checkInProgressIsPending() {
        // given
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-in-progress.json"));

        // when / then
        assertEquals(CommandStatus.PENDING, allegro(AllegroLabelFormat.PDF_A6).checkShipmentCreation("cmd-1", null).status());
    }

    @Test
    void checkOfACommandAllegroDoesNotKnowYetIsPending() {
        // given: a create command sent without an answer may still be saved later
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(404, "{\"errors\":[{\"code\":\"NOT_FOUND\"}]}"));

        // when / then
        assertEquals(CommandStatus.PENDING, allegro(AllegroLabelFormat.PDF_A6).checkShipmentCreation("cmd-1", null).status());
    }

    @Test
    void checkOfAFailedCommandStatesAllegrosReason() {
        // given: the 2026-10-06 sandbox answer for a made-up receiver e-mail
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-error-email.json"));

        // when
        ShipmentCreation failed = allegro(AllegroLabelFormat.PDF_A6).checkShipmentCreation("cmd-1", null);

        // then
        assertEquals(CommandStatus.FAILED, failed.status());
        assertEquals("Incorrect format of Allegro email", failed.error());
    }

    @Test
    void checkOfASuccessfulCommandReadsTheShipmentWithItsWaybill() {
        // given
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-success.json"));
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + SHIPMENT), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-dpd.json"));

        // when
        ShipmentCreation done = allegro(AllegroLabelFormat.PDF_A6).checkShipmentCreation("cmd-1", null);

        // then
        assertEquals(CommandStatus.SUCCEEDED, done.status());
        assertEquals(SHIPMENT, done.externalId());
        ShipmentResult.ShipmentParcelResult parcel = done.result().parcels().get(0);
        assertEquals("AD0W4SGTFDS2NWKJW", parcel.trackingNo());
        // Allegro answers carrier ALLEGRO for every Allegro Delivery shipment; the courier is in transport
        assertEquals("DPD", parcel.carrier());
        assertNull(parcel.trackingUrl());
        assertTrue(parcel.pickupRequired());
        assertTrue(parcel.cancellable());
    }

    @Test
    void oneByAllegroShipmentCannotBeCancelled() {
        // given: Allegro refuses to cancel it ("You cannot cancel a shipment with the Allegro carrier", sandbox 2026-10-08)
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(AllegroJson.MAPPER.valueToTree(java.util.Map.of(
                        "commandId", "cmd-1", "status", "SUCCESS", "shipmentId", ONE)));
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + ONE), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-one.json"));

        // when
        ShipmentResult.ShipmentParcelResult parcel = allegro(AllegroLabelFormat.PDF_A6)
                .checkShipmentCreation("cmd-1", null).result().parcels().get(0);

        // then
        assertEquals("A0000ADF87", parcel.trackingNo());
        assertEquals("ALLEGRO_ONE_KURIER", parcel.carrier());
        assertFalse(parcel.cancellable());
        assertTrue(parcel.pickupRequired());
    }

    @Test
    void shipmentWithoutTransportKeepsAllegrosCarrier() {
        // given
        ObjectNode shipment = Recordings.json("shipment-dpd.json").deepCopy();
        shipment.remove("transport");
        shipment.put("carrier", "DPD");
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-success.json"));
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + SHIPMENT), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(shipment);

        // when
        ShipmentResult.ShipmentParcelResult parcel = allegro(AllegroLabelFormat.PDF_A6)
                .checkShipmentCreation("cmd-1", null).result().parcels().get(0);

        // then
        assertEquals("DPD", parcel.carrier());
        assertTrue(parcel.cancellable());
    }

    @Test
    void shipmentNotReadableYetAfterASuccessfulCommandIsPendingWithItsId() {
        // given
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-success.json"));
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + SHIPMENT), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(404, ""));

        // when
        ShipmentCreation check = allegro(AllegroLabelFormat.PDF_A6).checkShipmentCreation("cmd-1", null);

        // then
        assertEquals(CommandStatus.PENDING, check.status());
        assertEquals(SHIPMENT, check.externalId());
    }

    @Test
    void shipmentWithoutWaybillYetIsStillPendingWithItsId() {
        // given
        when(restApi.fetchWithAuthRetry(eq(CREATE + "/cmd-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("create-command-success.json"));
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + SHIPMENT), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-no-waybill.json"));

        // when
        ShipmentCreation check = allegro(AllegroLabelFormat.PDF_A6).checkShipmentCreation("cmd-1", null);

        // then
        assertEquals(CommandStatus.PENDING, check.status());
        assertEquals(SHIPMENT, check.externalId());
    }
}
