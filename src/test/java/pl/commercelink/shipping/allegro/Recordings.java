package pl.commercelink.shipping.allegro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Reads a recorded Allegro answer from src/test/resources/recordings. */
final class Recordings {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Recordings() {
    }

    static JsonNode json(String name) {
        try (InputStream in = Recordings.class.getResourceAsStream("/recordings/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No recording " + name);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String name) {
        return json(name).toString();
    }
}
