package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.commercelink.rest.client.BinaryResponse;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.rest.client.RestApiWithRetry;
import pl.commercelink.shipping.api.Label;
import pl.commercelink.shipping.api.ShippingException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllegroLabelTest {

    private static final String DPD = "b7c6b01a-2916-4ae7-ae3d-570140674e25";
    private static final String ONE = "dde12f96-34d6-4513-8321-2d47a6b5251e";
    private static final String LABEL = "/shipment-management/label";

    @Mock
    private RestApiWithRetry restApi;

    @Test
    void allegroSupportsLabels() {
        assertTrue(new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6).supportsLabels());
    }

    @Test
    void pdfLabelIsDownloadedInThePageSizeOfTheSettings() {
        // given
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + DPD), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-dpd.json"));
        when(restApi.postForBytesWithAuthRetry(eq(LABEL), any(), eq("application/octet-stream")))
                .thenReturn(new BinaryResponse("%PDF-1.7".getBytes(), "application/octet-stream"));

        // when
        Label label = new AllegroShipping(restApi, AllegroLabelFormat.PDF_A4).getLabel(DPD);

        // then
        assertEquals("application/pdf", label.contentType());
        assertEquals("etykieta-AD0W4SGTFDS2NWKJW.pdf", label.fileName());
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restApi).postForBytesWithAuthRetry(eq(LABEL), body.capture(), eq("application/octet-stream"));
        JsonNode sent = AllegroJson.MAPPER.valueToTree(body.getValue());
        assertEquals(DPD, sent.path("shipmentIds").get(0).asText());
        assertEquals("A4", sent.path("pageSize").asText());
        assertFalse(sent.path("cutLine").asBoolean());
    }

    @Test
    void zplShipmentGetsAZplFileWhateverTheCurrentSettings() {
        // given: created as ZPL; the store switched to PDF later
        ObjectNode zplShipment = Recordings.json("shipment-one.json").deepCopy();
        zplShipment.put("labelFormat", "ZPL");
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + ONE), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(zplShipment);
        when(restApi.postForBytesWithAuthRetry(eq(LABEL), any(), anyString()))
                .thenReturn(new BinaryResponse("^XA^XZ".getBytes(), "application/octet-stream"));

        // when
        Label label = new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6).getLabel(ONE);

        // then
        assertEquals("text/plain", label.contentType());
        assertEquals("etykieta-A0000ADF87.zpl", label.fileName());
    }

    @Test
    void emptyLabelIsReportedAsNotReadyYet() {
        // given
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/" + DPD), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenReturn(Recordings.json("shipment-dpd.json"));
        when(restApi.postForBytesWithAuthRetry(eq(LABEL), any(), anyString()))
                .thenReturn(new BinaryResponse(new byte[0], "application/octet-stream"));

        // when / then
        assertThrows(ShippingException.class, () -> new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6).getLabel(DPD));
    }

    @Test
    void unknownShipmentIsAShippingExceptionWithTheHttpCause() {
        // given: the 2026-10-06 sandbox answer
        when(restApi.fetchWithAuthRetry(eq("/shipment-management/shipments/x"), anyMap(), anyMap(), eq(JsonNode.class)))
                .thenThrow(new HttpClientException(404, Recordings.text("shipment-not-found.json")));

        // when
        ShippingException e = assertThrows(ShippingException.class,
                () -> new AllegroShipping(restApi, AllegroLabelFormat.PDF_A6).getLabel("x"));

        // then
        assertEquals("Carriage not found", e.getMessage());
        assertInstanceOf(HttpClientException.class, e.getCause());
    }
}
