package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every {@code fileAttachments} descriptor a seed writes must be one the runtime
 * actually reads.
 *
 * <p>The two halves live far apart: an author edits
 * {@code scripts/api-migrations/*.json}, and {@link FileAttachmentResolver} reads
 * what the importer carried into the parameter's extras. A descriptor the
 * resolver rejects costs nothing at import and nothing at build. It costs the
 * attachment at run time: the file is dropped, the mail sends, and the run is
 * green. This closes that loop the way
 * {@code BodyTransformImplementationGuardTest} closes the transform one.
 *
 * <p>It also pins the seed that motivated the mechanism. Gmail could not send an
 * attachment at all, because {@code send_message} declared no parameter for one.
 */
@DisplayName("Seed fileAttachments descriptors")
class SeedFileAttachmentDescriptorsAreReadableTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FileAttachmentResolver resolver =
            new FileAttachmentResolver(null, objectMapper, 20L * 1024 * 1024);

    /** One descriptor found in a seed, with enough context to name it in a failure. */
    private record Declared(String api, String endpoint, String param, JsonNode descriptor, JsonNode parameter) {
        String where() {
            return api + "." + endpoint + "." + param;
        }
    }

    private static Path seedDirectory() {
        for (Path candidate : List.of(
                Paths.get("..", "..", "scripts", "api-migrations"),
                Paths.get("..", "scripts", "api-migrations"),
                Paths.get("scripts", "api-migrations"))) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("could not locate scripts/api-migrations from "
                + Path.of("").toAbsolutePath());
    }

    private List<Declared> declaredDescriptors() throws IOException {
        List<Declared> found = new ArrayList<>();
        try (Stream<Path> seeds = Files.list(seedDirectory())) {
            for (Path seed : seeds.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode root;
                try {
                    root = objectMapper.readTree(seed.toFile());
                } catch (IOException e) {
                    continue;   // not a seed; validate_apis.py owns malformed JSON
                }
                String api = seed.getFileName().toString().replace(".json", "");
                for (JsonNode endpoint : root.path("endpoints")) {
                    for (JsonNode param : endpoint.path("params")) {
                        JsonNode descriptor = param.path("fileAttachments");
                        if (descriptor.isObject()) {
                            found.add(new Declared(api, endpoint.path("name").asText(),
                                    param.path("name").asText(), descriptor, param));
                        }
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("the resolver accepts every descriptor a seed declares")
    void everyDescriptorParses() throws IOException {
        List<Declared> declared = declaredDescriptors();
        assertFalse(declared.isEmpty(), "no seed declares fileAttachments - the mechanism has no user");

        for (Declared d : declared) {
            String extras = objectMapper.createObjectNode().set("fileAttachments", d.descriptor()).toString();
            FileAttachmentResolver.Spec spec = resolver.parseSpec(extras);

            assertNotNull(spec, d.where() + ": the resolver rejects this descriptor, so the file "
                    + "would be dropped at run time on a call that reports success");
            assertFalse(spec.nameField().isBlank(), d.where() + ": nameField");
            assertFalse(spec.contentField().isBlank(), d.where() + ": contentField");
        }
    }

    @Test
    @DisplayName("a descriptor only ever sits on a body parameter that can carry file objects")
    void everyDescriptorSitsOnAUsableParameter() throws IOException {
        for (Declared d : declaredDescriptors()) {
            assertEquals("body", d.parameter().path("location").asText(),
                    d.where() + ": the resolver reads the value off the JSON body");
            // array only, not object: the resolver always produces a LIST, so an
            // object parameter would carry [{...}] where the provider wants {...}.
            assertEquals("array", d.parameter().path("type").asText(),
                    d.where() + ": the resolver always produces a list of attachments");
            assertFalse(d.parameter().path("inlineBody").asBoolean(false),
                    d.where() + ": the inlineBody passthrough returns before attachments are resolved");
        }
    }

    @Test
    @DisplayName("every Gmail endpoint that rebuilds the message can carry a file and a thread")
    void gmailCanAttachAndReply() throws IOException {
        JsonNode gmail = objectMapper.readTree(seedDirectory().resolve("gmail.json").toFile());

        // update_draft belongs here as much as the other two: it declares the same
        // rfc2822_draft transform, which rebuilds the WHOLE raw message, so a draft
        // it touches loses any attachment and any thread it cannot be given again.
        for (String endpointName : List.of("send_message", "create_draft", "update_draft")) {
            JsonNode endpoint = findByName(gmail.path("endpoints"), endpointName)
                    .orElseThrow(() -> new AssertionError("gmail." + endpointName + " is gone"));

            JsonNode attachments = findByName(endpoint.path("params"), "attachments")
                    .orElseThrow(() -> new AssertionError("gmail." + endpointName
                            + " declares no attachments parameter - this is exactly the gap that made "
                            + "sending a file with a Gmail message impossible"));
            assertEquals("content", attachments.path("fileAttachments").path("contentField").asText(),
                    "the rfc2822 transform reads the resolved objects by these field names");
            assertEquals("filename", attachments.path("fileAttachments").path("nameField").asText());

            assertTrue(findByName(endpoint.path("params"), "threadId").isPresent(),
                    "gmail." + endpointName + " must be able to answer inside a conversation");
        }
    }

    private Optional<JsonNode> findByName(JsonNode array, String name) {
        for (JsonNode node : array) {
            if (name.equals(node.path("name").asText())) return Optional.of(node);
        }
        return Optional.empty();
    }
}
