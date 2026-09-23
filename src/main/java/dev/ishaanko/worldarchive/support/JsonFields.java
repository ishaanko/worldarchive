package dev.ishaanko.worldarchive.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Strict readers for the fields of a Gson {@link JsonObject}. A field of the wrong type is an
 * error, never a silent default, and numbers must be exact integers. Each store keeps its own
 * exception type: create one instance per exception factory, for example
 * {@code new JsonFields<>(IOException::new)}, and share it.
 *
 * @param <E> the exception thrown for missing or malformed fields
 */
public final class JsonFields<E extends Exception> {
    private final BiFunction<String, Throwable, ? extends E> errors;

    /** The factory receives a message and the underlying cause, which may be null. */
    public JsonFields(BiFunction<String, Throwable, ? extends E> errors) {
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** Parses text that must hold one JSON object; the description names it in errors. */
    public JsonObject parseObject(String json, String description) throws E {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw failure(description + " is not valid JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw failure(description + " must be a JSON object", null);
        }
        return parsed.getAsJsonObject();
    }

    public JsonObject requiredObject(JsonObject object, String name) throws E {
        return optionalObject(object, name).orElseThrow(() -> missing("object", name));
    }

    /** An absent or null field is empty; any other non-object value is an error. */
    public Optional<JsonObject> optionalObject(JsonObject object, String name) throws E {
        JsonElement element = present(object, name);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            throw invalid("an object", name);
        }
        return Optional.of(element.getAsJsonObject());
    }

    public JsonArray requiredArray(JsonObject object, String name) throws E {
        JsonElement element = present(object, name);
        if (element == null || !element.isJsonArray()) {
            throw missing("array", name);
        }
        return element.getAsJsonArray();
    }

    /** An array whose every element must be an object. */
    public List<JsonObject> requiredObjects(JsonObject object, String name) throws E {
        JsonArray array = requiredArray(object, name);
        List<JsonObject> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw invalid("an array of objects", name);
            }
            values.add(element.getAsJsonObject());
        }
        return values;
    }

    /** An array whose every element must be a string. */
    public List<String> requiredStrings(JsonObject object, String name) throws E {
        JsonArray array = requiredArray(object, name);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!isString(element)) {
                throw invalid("an array of strings", name);
            }
            values.add(element.getAsString());
        }
        return values;
    }

    public String requiredString(JsonObject object, String name) throws E {
        return optionalString(object, name).orElseThrow(() -> missing("string", name));
    }

    /** An absent or null field is empty; any other non-string value is an error. */
    public Optional<String> optionalString(JsonObject object, String name) throws E {
        JsonElement element = present(object, name);
        if (element == null) {
            return Optional.empty();
        }
        if (!isString(element)) {
            throw invalid("a string", name);
        }
        return Optional.of(element.getAsString());
    }

    public boolean requiredBoolean(JsonObject object, String name) throws E {
        return optionalBoolean(object, name).orElseThrow(() -> missing("boolean", name));
    }

    /** An absent or null field is empty; any other non-boolean value is an error. */
    public Optional<Boolean> optionalBoolean(JsonObject object, String name) throws E {
        JsonElement element = present(object, name);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw invalid("a boolean", name);
        }
        return Optional.of(element.getAsBoolean());
    }

    /** A number that is exactly a {@code long}: no fraction and no overflow. */
    public long requiredLong(JsonObject object, String name) throws E {
        JsonElement element = present(object, name);
        if (element == null) {
            throw missing("integer", name);
        }
        return exactInteger(element, name).longValue();
    }

    /** A number that is exactly an {@code int}: no fraction and no overflow. */
    public int requiredInt(JsonObject object, String name) throws E {
        return optionalInt(object, name).orElseThrow(() -> missing("integer", name));
    }

    /** An absent or null field is empty; any other value must be an exact {@code int}. */
    public Optional<Integer> optionalInt(JsonObject object, String name) throws E {
        JsonElement element = present(object, name);
        if (element == null) {
            return Optional.empty();
        }
        BigDecimal value = exactInteger(element, name);
        if (value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw failure("Integer field is out of range: " + name, null);
        }
        return Optional.of(value.intValue());
    }

    /** An ISO-8601 instant such as {@code 2026-09-01T12:00:00Z}. */
    public Instant requiredInstant(JsonObject object, String name) throws E {
        String value = requiredString(object, name);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw failure("Field is not an ISO-8601 instant: " + name, exception);
        }
    }

    /** The exact name of one constant of the enum type. */
    public <T extends Enum<T>> T requiredEnum(JsonObject object, String name, Class<T> type) throws E {
        String value = requiredString(object, name);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw failure("Field has an unknown value: " + name, exception);
        }
    }

    private BigDecimal exactInteger(JsonElement element, String name) throws E {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid("an integer", name);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            BigDecimal value = new BigDecimal(primitive.getAsString());
            value.longValueExact();
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw failure("Field is not an exact integer: " + name, exception);
        }
    }

    private static JsonElement present(JsonObject object, String name) {
        JsonElement element = Objects.requireNonNull(object, "object").get(name);
        return element == null || element.isJsonNull() ? null : element;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private E missing(String type, String name) {
        return failure("Required " + type + " field is missing or invalid: " + name, null);
    }

    private E invalid(String type, String name) {
        return failure("Field must be " + type + ": " + name, null);
    }

    private E failure(String message, Throwable cause) {
        return errors.apply(message, cause);
    }
}
