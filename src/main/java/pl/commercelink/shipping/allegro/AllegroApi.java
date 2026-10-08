package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import pl.commercelink.rest.client.BinaryResponse;
import pl.commercelink.rest.client.RestApiWithRetry;

import java.util.HashMap;
import java.util.Map;

/**
 * Allegro calls of the adapter. Answers stay JSON trees; Polish is asked for, because errors[].userMessage is shown to
 * the operator as the carrier's own words.
 */
class AllegroApi {

    private static final Map<String, String> HEADERS = Map.of("Accept-Language", "pl-PL");

    private final RestApiWithRetry restApi;

    AllegroApi(RestApiWithRetry restApi) {
        this.restApi = restApi;
    }

    JsonNode get(String path) {
        return get(path, Map.of());
    }

    JsonNode get(String path, Map<String, String> params) {
        return restApi.fetchWithAuthRetry(path, new HashMap<>(params), HEADERS, JsonNode.class);
    }

    JsonNode post(String path, Object body) {
        return restApi.postWithAuthRetry(path, body, HEADERS, JsonNode.class);
    }

    BinaryResponse postForBytes(String path, Object body, String accept) {
        return restApi.postForBytesWithAuthRetry(path, body, accept);
    }
}
