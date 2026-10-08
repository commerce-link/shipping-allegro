package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.commercelink.shipping.api.Parcel;
import pl.commercelink.shipping.api.ShipmentAddress;
import pl.commercelink.shipping.api.ShipmentRequest;
import pl.commercelink.shipping.api.ShippingException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The input of a create command: Allegro's suggestedInput for the order (receiver with the masked e-mail, delivery
 * method, point) with what the shop decides on top: sender, the package, cash on delivery, insurance, label format,
 * reference number. Limits of the method are checked here, before anything is sent.
 */
final class AllegroShipmentInput {

    private static final String DEFAULT_CURRENCY = "PLN";

    private AllegroShipmentInput() {
    }

    static ObjectNode of(JsonNode proposal, ShipmentRequest request, AllegroLabelFormat labelFormat) {
        JsonNode suggested = proposal.path("suggestedInput");
        if (!suggested.isObject()) {
            throw new ShippingException("Allegro nie podało danych nadania dla zamówienia "
                    + request.orderReference().externalOrderId());
        }
        JsonNode option = AllegroProposals.primaryOption(proposal, cashOnDelivery(request) != null);
        JsonNode limits = option.path("limits");
        ObjectNode input = ((ObjectNode) suggested).deepCopy();
        // deprecated by Allegro: the method follows from the order; credentials only for own contracts
        input.remove("credentialsId");

        ShipmentAddress sender = request.sender() != null ? request.sender() : request.pickup();
        if (sender != null) {
            input.set("sender", sender(sender, suggested.path("sender")));
        }

        Parcel parcel = request.parcels().get(0);
        checkSize(parcel, AllegroProposals.packageOption(option));
        input.set("packages", AllegroJson.MAPPER.createArrayNode().add(pkg(parcel, option)));

        String currency = currency(limits);
        BigDecimal cashOnDelivery = cashOnDelivery(request);
        BigDecimal maxCashOnDelivery = AllegroJson.amount(limits.path("cashOnDelivery"));
        if (cashOnDelivery != null && maxCashOnDelivery != null && cashOnDelivery.compareTo(maxCashOnDelivery) > 0) {
            throw new ShippingException("Pobranie " + cashOnDelivery + " zł przekracza limit metody (" + maxCashOnDelivery + " zł).");
        }
        if (cashOnDelivery != null) {
            ShipmentRequestCod cod = ShipmentRequestCod.of(request);
            ObjectNode node = amount(cashOnDelivery, currency);
            node.put("ownerName", cod.owner());
            node.put("iban", cod.iban());
            input.set("cashOnDelivery", node);
        } else {
            input.remove("cashOnDelivery");
        }

        BigDecimal insurance = BigDecimal.valueOf(parcel.insuranceValue()).setScale(2, RoundingMode.HALF_UP);
        if (cashOnDelivery != null && cashOnDelivery.compareTo(insurance) > 0) {
            insurance = cashOnDelivery;
        }
        BigDecimal maxInsurance = AllegroJson.amount(limits.path("insurance"));
        if (maxInsurance != null && insurance.compareTo(maxInsurance) > 0) {
            throw new ShippingException("Ubezpieczenie " + insurance + " zł przekracza limit metody (" + maxInsurance + " zł).");
        }
        if (insurance.signum() > 0) {
            input.set("insurance", amount(insurance, currency));
        } else {
            input.remove("insurance");
        }

        input.put("labelFormat", labelFormat.fileFormat());
        String orderNumber = request.orderReference().orderNumber();
        if (orderNumber != null && !orderNumber.isBlank()) {
            input.put("referenceNumber", orderNumber);
        }
        return input;
    }

    private static ObjectNode sender(ShipmentAddress address, JsonNode suggestedSender) {
        ObjectNode node = AllegroJson.object();
        putIfPresent(node, "name", address.name());
        putIfPresent(node, "company", address.company());
        putIfPresent(node, "street", address.street());
        putIfPresent(node, "postalCode", address.postcode());
        putIfPresent(node, "city", address.city());
        putIfPresent(node, "countryCode", address.countryCode());
        putIfPresent(node, "email", address.email());
        boolean polish = address.countryCode() == null || "PL".equalsIgnoreCase(address.countryCode());
        putIfPresent(node, "phone", polish ? phone(address.phone()) : address.phone());
        String point = AllegroJson.text(suggestedSender, "point");
        if (point != null) {
            node.put("point", point);
        }
        return node;
    }

    static ObjectNode address(ShipmentAddress address) {
        return sender(address, null);
    }

    private static final Pattern POLISH_NATIONAL = Pattern.compile("\\d{9}");

    /**
     * Allegro refuses a phone without the international prefix (VALIDATION_ERROR on sender.phone and on the pickup
     * address.phone, sandbox 2026-10-08), while shops usually keep Polish numbers as nine digits.
     */
    static String phone(String phone) {
        if (phone == null || phone.isBlank() || phone.strip().startsWith("+")) {
            return phone;
        }
        String digits = phone.replaceAll("[\\s-]", "");
        if (digits.startsWith("0048") && POLISH_NATIONAL.matcher(digits.substring(4)).matches()) {
            return "+48" + digits.substring(4);
        }
        return POLISH_NATIONAL.matcher(digits).matches() ? "+48" + digits : phone;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static ObjectNode pkg(Parcel parcel, JsonNode option) {
        String type = AllegroJson.text(option, "packageType");
        ObjectNode node = AllegroJson.object();
        node.put("type", type == null ? "PACKAGE" : type);
        node.set("length", measure(parcel.depth(), "CENTIMETER"));
        node.set("width", measure(parcel.width(), "CENTIMETER"));
        node.set("height", measure(parcel.height(), "CENTIMETER"));
        node.set("weight", measure(parcel.weight(), "KILOGRAMS"));
        return node;
    }

    private static ObjectNode measure(int value, String unit) {
        ObjectNode node = AllegroJson.object();
        node.put("value", value);
        node.put("unit", unit);
        return node;
    }

    private static ObjectNode amount(BigDecimal value, String currency) {
        ObjectNode node = AllegroJson.object();
        node.put("amount", value.setScale(2, RoundingMode.HALF_UP).toPlainString());
        node.put("currency", currency);
        return node;
    }

    private static String currency(JsonNode limits) {
        String currency = AllegroJson.text(limits.path("insurance"), "currency");
        if (currency == null) {
            currency = AllegroJson.text(limits.path("cashOnDelivery"), "currency");
        }
        return currency == null ? DEFAULT_CURRENCY : currency;
    }

    private static BigDecimal cashOnDelivery(ShipmentRequest request) {
        if (request.options() == null || !request.options().hasCashOnDelivery()) {
            return null;
        }
        BigDecimal amount = BigDecimal.valueOf(request.options().cashOnDelivery().amount()).setScale(2, RoundingMode.HALF_UP);
        return amount.signum() > 0 ? amount : null;
    }

    /**
     * Sides of the parcel against the method's limits, longest against longest: Allegro does not say which side is the
     * length, and the shop's templates do not either.
     */
    private static void checkSize(Parcel parcel, pl.commercelink.shipping.api.PackageOption option) {
        if (option.maxLength() != null && option.maxWidth() != null && option.maxHeight() != null) {
            int[] sides = {parcel.depth(), parcel.width(), parcel.height()};
            Arrays.sort(sides);
            BigDecimal[] max = {option.maxLength(), option.maxWidth(), option.maxHeight()};
            Arrays.sort(max);
            for (int i = 0; i < 3; i++) {
                if (BigDecimal.valueOf(sides[i]).compareTo(max[i]) > 0) {
                    throw new ShippingException("Paczka przekracza limity metody: wymiary " + parcel.depth() + "×"
                            + parcel.width() + "×" + parcel.height() + " cm (maks. " + plain(max[2]) + "×"
                            + plain(max[1]) + "×" + plain(max[0]) + " cm).");
                }
            }
        }
        if (option.maxWeight() != null && BigDecimal.valueOf(parcel.weight()).compareTo(option.maxWeight()) > 0) {
            throw new ShippingException("Paczka przekracza limity metody: waga " + parcel.weight() + " kg (maks. "
                    + plain(option.maxWeight()) + " kg).");
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Owner and IBAN of the cash on delivery from the request. */
    private record ShipmentRequestCod(String owner, String iban) {
        static ShipmentRequestCod of(ShipmentRequest request) {
            var cod = Objects.requireNonNull(request.options().cashOnDelivery());
            return new ShipmentRequestCod(cod.accountHolder(), cod.iban());
        }
    }
}
