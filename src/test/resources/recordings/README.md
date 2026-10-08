# Recorded Allegro answers

## Real sandbox answers, 2026-10-06 (seller SklepTestowyCL, orders on the seller's own carrier contract)

`delivery-services.json`, `delivery-proposal-unsupported.json`, `create-command-accepted.json`,
`create-command-error-email.json`, `create-command-error-method.json`, `cancel-command-error.json`,
`shipment-not-found.json`, `pickup-proposals-missing-address.json`, `tracking-no-details.json`, `forbidden.json`,
`checkout-form-own-dpd.json` (trimmed to `id` and `delivery`).

## Real sandbox answers, 2026-10-08 (seller SklepTestowyCL, orders on Allegro Delivery methods)

Recorded through the real adapter (jar 0.1.0) and `curl` against `api.allegro.pl.allegrosandbox.pl`. Buyer data is
masked (phone `+48500600701`, e-mail local part, the seller's own e-mail in `suggestedInput.sender`); ids, waybills and
structure are as Allegro sent them.

| File | Order / shipment |
|---|---|
| `delivery-proposal-dpd.json`, `checkout-form-allegro-dpd.json` (trimmed to `id`, `payment.type`, `delivery`) | order `3daafd00-…`, "Allegro Kurier DPD (AD)" |
| `delivery-proposal-locker.json`, `checkout-form-allegro-locker.json` (trimmed to `id`, `payment.type`, `delivery`) | order `87e369f0-…`, "Allegro Paczkomaty InPost", point WRO226M |
| `create-command-success.json`, `shipment-dpd.json`, `pickup-proposals.json` | DPD shipment `b7c6b01a-…`, waybill `AD0W4SGTFDS2NWKJW` |
| `create-command-in-progress.json`, `create-command-error-postcode.json` | a create command right after it was sent, then its error (made-up buyer post code) |
| `create-command-error-phone.json` | sender phone without the `+48` prefix |
| `pickup-proposals-phone-without-prefix.json` | HTTP 400 body: pickup address phone without the `+48` prefix |
| `create-command-error-inpost-credentials.json` | Allegro InPost methods: the seller has no InPost account linked |
| `create-command-duplicate.json` | body of the **HTTP 400** `COMMAND_EXISTS` for a create command id sent twice (cancel commands answer the same; used for pickup commands too) |
| `shipment-one.json` | One by Allegro shipment `dde12f96-…`, waybill `A0000ADF87` |
| `pickup-proposals-two-carriers.json` | the DPD and the One shipment asked for together: one group per courier |
| `cancel-command-success.json` | cancelled Allegro DHL (AD) shipment `d432d229-…` |
| `cancel-command-error-one.json` | One by Allegro cannot be cancelled |
| `cancel-command-error-carrier-unsupported.json` | Allegro DPD (AD) shipment: the sandbox refuses the cancellation |
| `cancel-command-error-already-cancelled.json` | second cancellation of the cancelled DHL shipment |

## Synthetic, shaped after the Allegro OpenAPI (`developer.allegro.pl/swagger.yaml`, 2026-10-08)

- `pickup-command-success.json`, `pickup-command-error.json`: ordering a pickup would call a courier, so it was not
  done on the sandbox.
- `tracking-in-transit.json`, `tracking-delivered.json`: the sandbox has no tracking statuses (`trackingDetails: null`
  for every waybill, Allegro Delivery ones included). Waybill and carrier match `shipment-dpd.json`.
- `pickup-proposals-two-shipments.json`: two shipments of one courier with partly common windows (the intersection
  logic); the sandbox account had no two live shipments of one courier.
- `shipment-no-waybill.json`: a shipment read before its waybill exists.
