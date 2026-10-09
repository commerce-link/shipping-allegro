package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** Reading helpers for Allegro answers, which the adapter keeps as JSON trees (fields come and go between versions). */
final class AllegroJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AllegroJson() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** A non-blank text field, else null (absent, JSON null, empty). */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    /** An Allegro amount object {"amount": "12.50", "currency": "PLN"} as a number (a comma decimal is accepted), else null. */
    static BigDecimal amount(JsonNode amountObject) {
        String amount = text(amountObject, "amount");
        return amount == null ? null : new BigDecimal(amount.strip().replace(',', '.'));
    }

    /** An Allegro measure object {"value": 20, "unit": "CENTIMETER"} as a number, else null. */
    static BigDecimal measure(JsonNode measureObject) {
        JsonNode value = measureObject == null ? null : measureObject.get("value");
        return value == null || !value.isNumber() ? null : value.decimalValue();
    }
}
