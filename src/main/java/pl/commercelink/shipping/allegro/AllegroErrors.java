package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import pl.commercelink.rest.client.HttpClientException;
import pl.commercelink.shipping.api.ShippingException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Allegro's error shape {"errors": [{"code", "message", "userMessage", ...}]} and how the adapter reports it. */
final class AllegroErrors {

    static final String NO_SHIPMENTS_CONSENT =
            "Brak zgody na przesyłki w aplikacji Allegro — włącz ją i połącz Allegro ponownie.";

    private AllegroErrors() {
    }

    /** The operator-facing words of each error (userMessage, else message), in order, without blanks. */
    static List<String> messages(JsonNode holder) {
        List<String> messages = new ArrayList<>();
        if (holder == null) {
            return messages;
        }
        for (JsonNode error : holder.path("errors")) {
            String text = AllegroJson.text(error, "userMessage");
            if (text == null) {
                text = AllegroJson.text(error, "message");
            }
            if (text != null) {
                messages.add(text);
            }
        }
        return messages;
    }

    static List<String> messages(HttpClientException ex) {
        String body = ex.getResponseBody();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            return messages(AllegroJson.MAPPER.readTree(body));
        } catch (Exception notJson) {
            return List.of();
        }
    }

    static boolean hasCode(HttpClientException ex, String code) {
        String body = ex.getResponseBody();
        if (body == null) {
            return false;
        }
        try {
            for (JsonNode error : AllegroJson.MAPPER.readTree(body).path("errors")) {
                if (code.equals(AllegroJson.text(error, "code"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception notJson) {
            return false;
        }
    }

    /** A 4xx Allegro certainly did not process; 408 is excluded because the request may have been handled before the timeout. */
    static boolean isClientError(HttpClientException ex) {
        return ex.getStatusCode() >= 400 && ex.getStatusCode() < 500 && ex.getStatusCode() != 408;
    }

    /** Errors of a command status answer joined for the operator; the fallback when Allegro gave none. */
    static String joined(JsonNode status, String fallback) {
        String joined = String.join("; ", messages(status));
        return joined.isEmpty() ? fallback : joined;
    }

    /** An HTTP failure as a ShippingException that keeps the HTTP cause (the SPI contract) and Allegro's words. */
    static ShippingException exception(HttpClientException ex) {
        List<String> messages = messages(ex);
        String message = ex.getStatusCode() == 403
                ? NO_SHIPMENTS_CONSENT
                : messages.isEmpty() ? "Allegro odpowiedziało HTTP " + ex.getStatusCode()
                : messages.stream().collect(Collectors.joining("; "));
        return new ShippingException(message, ex, messages);
    }

    /** Wraps an HTTP failure of a read; anything else passes unchanged. */
    static RuntimeException failure(RuntimeException ex) {
        return ex instanceof HttpClientException http ? exception(http) : ex;
    }
}
