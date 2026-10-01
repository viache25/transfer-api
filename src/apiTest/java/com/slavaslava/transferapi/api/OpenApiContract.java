package com.slavaslava.transferapi.api;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract check against the app's own {@code /v3/api-docs}: the operation must be documented and
 * every property the documented response schema declares must be present in the real body with a
 * compatible JSON type.
 */
final class OpenApiContract {

    private final Map<String, Object> spec;

    OpenApiContract(int port) {
        this.spec = given().port(port).get("/v3/api-docs").then().statusCode(200).extract().jsonPath().getMap("$");
    }

    void assertConforms(String method, String pathTemplate, Response response) {
        Map<String, Object> operation = operation(method, pathTemplate);
        Map<String, Object> schema = successSchema(operation);
        JsonPath body = response.jsonPath();
        Map<String, Object> properties = properties(schema);
        assertThat(properties).as("schema properties of %s %s", method, pathTemplate).isNotEmpty();
        properties.forEach((name, definition) -> {
            Object value = body.get(name);
            assertThat(value).as("%s %s: property '%s' declared in the spec", method, pathTemplate, name).isNotNull();
            assertType(method + " " + pathTemplate + " ." + name, resolve(asMap(definition)), value);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> operation(String method, String pathTemplate) {
        Map<String, Object> paths = asMap(spec.get("paths"));
        assertThat(paths).as("documented paths").containsKey(pathTemplate);
        Map<String, Object> item = asMap(paths.get(pathTemplate));
        assertThat(item).as("operations of %s", pathTemplate).containsKey(method.toLowerCase());
        return (Map<String, Object>) item.get(method.toLowerCase());
    }

    private Map<String, Object> successSchema(Map<String, Object> operation) {
        Map<String, Object> responses = asMap(operation.get("responses"));
        String code = responses.keySet().stream().filter(c -> c.startsWith("2")).findFirst().orElseThrow();
        Map<String, Object> content = asMap(asMap(responses.get(code)).get("content"));
        Map<String, Object> media = asMap(content.values().iterator().next());
        return resolve(asMap(media.get("schema")));
    }

    private Map<String, Object> properties(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return properties == null ? Map.of() : asMap(properties);
    }

    private Map<String, Object> resolve(Map<String, Object> schema) {
        Object ref = schema.get("$ref");
        if (ref == null) {
            return schema;
        }
        String name = ref.toString().substring(ref.toString().lastIndexOf('/') + 1);
        return asMap(asMap(asMap(spec.get("components")).get("schemas")).get(name));
    }

    private void assertType(String where, Map<String, Object> definition, Object value) {
        Object declared = definition.get("type");
        if (declared == null) {
            return;
        }
        // OpenAPI 3.1 allows a type array such as ["string", "null"]
        List<?> types = declared instanceof List<?> list ? list : List.of(declared);
        boolean matches = types.stream().anyMatch(t -> switch (t.toString()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;
        });
        assertThat(matches).as("%s should be %s but was %s", where, types, value.getClass().getSimpleName()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
