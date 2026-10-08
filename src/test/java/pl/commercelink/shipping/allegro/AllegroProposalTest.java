package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.*;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllegroProposalTest {

    private static final String DPD_ORDER = "3daafd00-c31d-11f1-ac0d-1b8fc439c8a2";
    private static final String LOCKER_ORDER = "87e369f0-c31a-11f1-ac0d-1b8fc439c8a2";

    @Mock
    private RestApiWithRetry restApi;

    private AllegroShipping allegro() {
        return new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6);
    }

    /** The real proposal with the method id the OpenAPI documents; the sandbox leaves it null. */
    private void proposalWithSuggestedMethod() {
        ObjectNode proposal = Recordings.json("delivery-proposal-dpd.json").deepCopy();
        ((ObjectNode) proposal.path("suggestedInput")).put("deliveryMethodId", "03f26351-f683-4db2-8895-f2c599280e3c");
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-proposals/" + DPD_ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(proposal);
    }

    private void answer(String path, String recording) {
        when(restApi.fetchWithAuthRetry(eq(path), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json(recording));
    }

    @Test
    void allegroSupportsProposals() {
        // when / then
        assertTrue(allegro().supportsShipmentProposals());
    }

    @Test
    void proposalForAnAllegroDeliveryOrderCarriesMethodAndLimitsOfThePrepaidParcel() {
        // given
        answer("/shipment-management/delivery-proposals/" + DPD_ORDER, "delivery-proposal-dpd.json");
        answer("/order/checkout-forms/" + DPD_ORDER, "checkout-form-allegro-dpd.json");

        // when
        ShipmentProposal proposal = allegro().proposeShipment(new OrderReference("Allegro", DPD_ORDER, "CL-SANDBOX"));

        // then
        assertTrue(proposal.available());
        assertEquals("Allegro Kurier DPD (AD)", proposal.methodName());
        // the sandbox (2026-10-08) leaves suggestedInput.deliveryMethodId null, so no carrier is looked up
        assertNull(proposal.carrierId());
        assertEquals(DeliveryType.DOOR, proposal.deliveryType());
        assertNull(proposal.deliveryPoint());
        assertNull(proposal.maxCashOnDelivery());
        assertEquals(0, new BigDecimal("15000").compareTo(proposal.maxInsurance()));
        assertEquals(2, proposal.packageOptions().size());
        PackageOption option = proposal.packageOptions().get(0);
        assertEquals("PACKAGE", option.type());
        assertEquals(0, new BigDecimal("150").compareTo(option.maxLength()));
        assertEquals(0, new BigDecimal("300").compareTo(option.maxWeight()));
    }

    @Test
    void lockerProposalHasThePointAndNoCashOnDelivery() {
        // given
        answer("/shipment-management/delivery-proposals/" + LOCKER_ORDER, "delivery-proposal-locker.json");
        answer("/order/checkout-forms/" + LOCKER_ORDER, "checkout-form-allegro-locker.json");

        // when
        ShipmentProposal proposal = allegro().proposeShipment(new OrderReference("Allegro", LOCKER_ORDER, "CL-1002"));

        // then
        assertTrue(proposal.available());
        assertEquals(DeliveryType.LOCKER, proposal.deliveryType());
        assertEquals("WRO226M", proposal.deliveryPoint().code());
        assertEquals("Allegro Paczkomaty InPost", proposal.methodName());
        assertNull(proposal.maxCashOnDelivery());
    }

    @Test
    void orderOnTheSellersOwnContractIsUnavailableWithAReason() {
        // given: the 2026-10-06 sandbox answer for an order on the seller's own DPD contract
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-proposals/own-1"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(400, Recordings.text("delivery-proposal-unsupported.json")));

        // when
        ShipmentProposal proposal = allegro().proposeShipment(new OrderReference("Allegro", "own-1", "CL-1003"));

        // then
        assertFalse(proposal.available());
        assertEquals(AllegroShipping.UNSUPPORTED_METHOD, proposal.unavailableReason());
        verify(restApi, never()).fetchWithAuthRetry(startsWith("/order/checkout-forms/"), anyMap(), anyMap(), any());
    }

    @Test
    void proposalWithoutDeliveryOptionsIsUnavailable() {
        // given
        JsonNode noOptions = Recordings.json("delivery-proposal-dpd.json").deepCopy();
        ((ObjectNode) noOptions).putArray("deliveryOptions");
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-proposals/" + DPD_ORDER), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(noOptions);
        answer("/order/checkout-forms/" + DPD_ORDER, "checkout-form-allegro-dpd.json");

        // when
        ShipmentProposal proposal = allegro().proposeShipment(new OrderReference("Allegro", DPD_ORDER, "CL-SANDBOX"));

        // then
        assertFalse(proposal.available());
        assertEquals(AllegroProposals.NO_OPTIONS, proposal.unavailableReason());
    }

    @Test
    void suggestedDeliveryMethodIsLookedUpForItsCarrier() {
        // given
        proposalWithSuggestedMethod();
        answer("/order/checkout-forms/" + DPD_ORDER, "checkout-form-allegro-dpd.json");
        answer("/shipment-management/delivery-services", "delivery-services.json");

        // when
        ShipmentProposal proposal = allegro().proposeShipment(new OrderReference("Allegro", DPD_ORDER, "CL-SANDBOX"));

        // then
        assertEquals("ALLEGRO", proposal.carrierId());
    }

    @Test
    void carrierLookupFailureLeavesTheCarrierEmptyButTheProposalAvailable() {
        // given
        proposalWithSuggestedMethod();
        answer("/order/checkout-forms/" + DPD_ORDER, "checkout-form-allegro-dpd.json");
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-services"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(503, ""));

        // when
        ShipmentProposal proposal = allegro().proposeShipment(new OrderReference("Allegro", DPD_ORDER, "CL-SANDBOX"));

        // then
        assertTrue(proposal.available());
        assertNull(proposal.carrierId());
    }

    @Test
    void otherRefusalOfTheProposalIsAShippingExceptionWithTheHttpCause() {
        // given
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/delivery-proposals/gone"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(404, "{\"errors\":[{\"code\":\"NOT_FOUND\",\"userMessage\":\"Nie znaleziono zamówienia\"}]}"));

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> allegro().proposeShipment(new OrderReference("Allegro", "gone", "CL-1004")));

        // then
        assertEquals("Nie znaleziono zamówienia", e.getMessage());
        assertInstanceOf(HttpClientException.class, e.getCause());
    }
}
