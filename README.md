# Shipping Allegro

"Wysyłam z Allegro" (Ship with Allegro) shipping provider for the CommerceLink platform: creates shipments for
Allegro orders on Allegro Delivery prices, downloads labels, orders courier pickups, cancels shipments and reads
parcel tracking through the Allegro REST API (`/shipment-management/*`, `/order/carriers/*/tracking`).

Implements the `ShippingProviderDescriptor` SPI from [shipping-api](https://github.com/commerce-link/shipping-api)
(0.6.0+). The provider name is `allegro`.

## Authorization

The adapter has no credentials of its own: the application builds its `RestApiWithRetry` from the store's Allegro
marketplace connection (the same OAuth2 tokens, see `marketplace-allegro`). The Allegro application must have the
`allegro:api:shipments:read` and `allegro:api:shipments:write` scopes; without them every call answers 403.

API, token and device URLs follow the same `ALLEGRO_API_URL`, `ALLEGRO_TOKEN_URL`, `ALLEGRO_DEVICE_URL` system
properties / environment variables as `marketplace-allegro` (sandbox: `https://api.allegro.pl.allegrosandbox.pl`).

## Configuration

| Key | Values | Default |
|---|---|---|
| `labelFormat` | `PDF_A6`, `PDF_A4`, `ZPL` | `PDF_A6` |

## Limits

- Only Allegro orders on Allegro Delivery methods: an order on the seller's own carrier contract is reported as
  unavailable (`UNSUPPORTED_DELIVERY_METHOD`).
- One package per shipment: every box is a separate shipment.
- No webhooks: tracking is read with `getTrackingEvents` (`supportsTrackingPolling() == true`).
