package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FileAttachmentResolver}.
 *
 * <p>The class exists because a mail API on a JSON body asks for base64 that no
 * surface of the platform can produce, so these tests are written around the two
 * things that used to go wrong: a file that never reached the provider, and a
 * failure that reported success.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileAttachmentResolver")
class FileAttachmentResolverTest {

    private static final long PLATFORM_MAX = 20L * 1024 * 1024;

    @Mock
    private StorageClient storageClient;

    private FileAttachmentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FileAttachmentResolver(storageClient, new ObjectMapper(), PLATFORM_MAX);
    }

    private static Map<String, Object> fileRef(String path, String name, String mimeType) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", path);
        ref.put("name", name);
        ref.put("mimeType", mimeType);
        return ref;
    }

    private FileAttachmentResolver.Spec spec(String extras) {
        FileAttachmentResolver.Spec parsed = resolver.parseSpec(extras);
        assertNotNull(parsed, "descriptor should have parsed");
        return parsed;
    }

    @Nested
    @DisplayName("parseSpec()")
    class ParseSpecTests {

        @Test
        @DisplayName("should return null when the parameter declares no attachments")
        void shouldReturnNullWithoutDescriptor() {
            assertNull(resolver.parseSpec(null));
            assertNull(resolver.parseSpec(""));
            assertNull(resolver.parseSpec("{}"));
            assertNull(resolver.parseSpec("{\"bodyPath\":\"message.attachments\"}"));
        }

        @Test
        @DisplayName("should read the provider field names")
        void shouldReadFieldNames() {
            FileAttachmentResolver.Spec parsed = spec(
                    "{\"fileAttachments\":{\"nameField\":\"Name\",\"contentField\":\"Content\",\"mimeField\":\"ContentType\"}}");

            assertEquals("Name", parsed.nameField());
            assertEquals("Content", parsed.contentField());
            assertEquals("ContentType", parsed.mimeField());
            assertFalse(parsed.dataUrl());
            assertEquals(PLATFORM_MAX, parsed.maxTotalBytes());
        }

        @Test
        @DisplayName("should leave mimeField null for a provider that has no such field")
        void shouldAllowMissingMimeField() {
            FileAttachmentResolver.Spec parsed = spec(
                    "{\"fileAttachments\":{\"nameField\":\"name\",\"contentField\":\"content\"}}");

            assertNull(parsed.mimeField());
        }

        @Test
        @DisplayName("should ignore a descriptor missing the fields it cannot work without")
        void shouldIgnoreIncompleteDescriptor() {
            assertNull(resolver.parseSpec("{\"fileAttachments\":{\"nameField\":\"name\"}}"));
            assertNull(resolver.parseSpec("{\"fileAttachments\":{\"contentField\":\"content\"}}"));
        }

        @Test
        @DisplayName("should ignore malformed extras rather than fail the call")
        void shouldIgnoreMalformedExtras() {
            assertNull(resolver.parseSpec("not json"));
        }

        @Test
        @DisplayName("should keep the endpoint ceiling when it is below the platform one")
        void shouldKeepLowerEndpointCeiling() {
            FileAttachmentResolver.Spec parsed = spec(
                    "{\"fileAttachments\":{\"nameField\":\"n\",\"contentField\":\"c\",\"maxTotalBytes\":3500000}}");

            assertEquals(3_500_000L, parsed.maxTotalBytes());
        }

        @Test
        @DisplayName("should never let an endpoint raise the ceiling above the platform one")
        void shouldClampCeilingToPlatformMax() {
            FileAttachmentResolver.Spec parsed = spec(
                    "{\"fileAttachments\":{\"nameField\":\"n\",\"contentField\":\"c\",\"maxTotalBytes\":99999999999}}");

            assertEquals(PLATFORM_MAX, parsed.maxTotalBytes());
        }

        @Test
        @DisplayName("should read constant fields a provider requires on every attachment")
        void shouldReadConstantFields() {
            FileAttachmentResolver.Spec parsed = spec(
                    "{\"fileAttachments\":{\"nameField\":\"name\",\"contentField\":\"contentBytes\","
                            + "\"constantFields\":{\"@odata.type\":\"#microsoft.graph.fileAttachment\"}}}");

            assertEquals(Map.of("@odata.type", "#microsoft.graph.fileAttachment"), parsed.constantFields());
        }

        @Test
        @DisplayName("should read the data_url encoding")
        void shouldReadDataUrlEncoding() {
            assertTrue(spec("{\"fileAttachments\":{\"nameField\":\"n\",\"contentField\":\"c\",\"encoding\":\"data_url\"}}").dataUrl());
        }
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        // Built in a @BeforeEach, not a field initializer: a nested class is
        // constructed before the outer @BeforeEach has made the resolver.
        private FileAttachmentResolver.Spec sendgrid;

        @BeforeEach
        void buildSpec() {
            sendgrid = spec("{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"mimeField\":\"type\"}}");
        }

        @Test
        @DisplayName("should turn a file handle into the provider's attachment object")
        void shouldConvertFileRef() {
            when(storageClient.download("tenant-1", "tenant-1/report.pdf")).thenReturn("PDF".getBytes());

            List<Object> resolved = resolver.resolve("attachments",
                    List.of(fileRef("tenant-1/report.pdf", "report.pdf", "application/pdf")),
                    sendgrid, "tenant-1");

            assertEquals(1, resolved.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> attachment = (Map<String, Object>) resolved.get(0);
            assertEquals("report.pdf", attachment.get("filename"));
            assertEquals("application/pdf", attachment.get("type"));
            assertEquals(Base64.getEncoder().encodeToString("PDF".getBytes()), attachment.get("content"));
        }

        @Test
        @DisplayName("should accept a single file as well as a list")
        void shouldAcceptSingleFile() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());

            List<Object> resolved = resolver.resolve("attachments",
                    fileRef("t/a.txt", "a.txt", "text/plain"), sendgrid, "t");

            assertEquals(1, resolved.size());
        }

        @Test
        @DisplayName("should read the bytes under the tenant that owns them")
        void shouldDownloadUnderOwningTenant() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());

            resolver.resolve("attachments", fileRef("t/a.txt", "a.txt", "text/plain"), sendgrid, "tenant-42");

            verify(storageClient).download(eq("tenant-42"), eq("t/a.txt"));
        }

        @Test
        @DisplayName("should leave Resend's documented {filename, path} form alone instead of refusing the mail")
        void shouldNotMistakeARemotePathForAStorageKey() {
            // Resend's own seed documents "optional path/content_type", where path
            // is a URL Resend fetches itself. Reading any non-blank path as a
            // storage key made the platform look for a file under a URL, fail, and
            // refuse the ENTIRE mail with a message blaming a deleted file.
            Map<String, Object> resendStyle = new LinkedHashMap<>();
            resendStyle.put("filename", "report.pdf");
            resendStyle.put("path", "https://example.com/report.pdf");

            List<Object> resolved = resolver.resolve("attachments", List.of(resendStyle), sendgrid, "t");

            assertSame(resendStyle, resolved.get(0));
            verifyNoInteractions(storageClient);
        }

        @Test
        @DisplayName("should not treat a bare path with no name as a file")
        void shouldRequireNameOrTypeMarkerBesidePath() {
            // A FileRef always carries the marker or the name. A lone path is a
            // provider field that happens to share the word.
            Map<String, Object> pathOnly = new LinkedHashMap<>();
            pathOnly.put("path", "some/remote/location");

            assertSame(pathOnly, resolver.resolve("attachments", List.of(pathOnly), sendgrid, "t").get(0));
            verifyNoInteractions(storageClient);
        }

        @Test
        @DisplayName("should still recognise a file whose _type marker was lost in a template round-trip")
        void shouldRecogniseFileRefWithoutTypeMarker() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());
            Map<String, Object> stripped = new LinkedHashMap<>();
            stripped.put("path", "t/a.txt");
            stripped.put("name", "a.txt");

            @SuppressWarnings("unchecked")
            Map<String, Object> attachment =
                    (Map<String, Object>) resolver.resolve("attachments", stripped, sendgrid, "t").get(0);

            assertEquals("a.txt", attachment.get("filename"));
        }

        @Test
        @DisplayName("should leave an object the caller built itself untouched")
        void shouldPassThroughHandBuiltObject() {
            Map<String, Object> handBuilt = Map.of("filename", "x.txt", "content", "aGk=");

            List<Object> resolved = resolver.resolve("attachments", List.of(handBuilt), sendgrid, "t");

            assertSame(handBuilt, resolved.get(0));
            verifyNoInteractions(storageClient);
        }

        @Test
        @DisplayName("should carry the provider's constant fields onto every attachment")
        void shouldCarryConstantFields() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());
            FileAttachmentResolver.Spec graph = spec(
                    "{\"fileAttachments\":{\"nameField\":\"name\",\"contentField\":\"contentBytes\","
                            + "\"mimeField\":\"contentType\","
                            + "\"constantFields\":{\"@odata.type\":\"#microsoft.graph.fileAttachment\"}}}");

            @SuppressWarnings("unchecked")
            Map<String, Object> attachment = (Map<String, Object>) resolver.resolve(
                    "attachments", fileRef("t/a.txt", "a.txt", "text/plain"), graph, "t").get(0);

            assertEquals("#microsoft.graph.fileAttachment", attachment.get("@odata.type"));
            assertEquals("a.txt", attachment.get("name"));
            assertEquals("text/plain", attachment.get("contentType"));
        }

        @Test
        @DisplayName("should emit a data URL when the provider takes one")
        void shouldEmitDataUrl() {
            when(storageClient.download(any(), any())).thenReturn("PNG".getBytes());
            FileAttachmentResolver.Spec dataUrlSpec = spec(
                    "{\"fileAttachments\":{\"nameField\":\"n\",\"contentField\":\"c\",\"encoding\":\"data_url\"}}");

            @SuppressWarnings("unchecked")
            Map<String, Object> attachment = (Map<String, Object>) resolver.resolve(
                    "attachments", fileRef("t/a.png", "a.png", "image/png"), dataUrlSpec, "t").get(0);

            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString("PNG".getBytes()),
                    attachment.get("c"));
        }

        @Test
        @DisplayName("should omit the media type when the provider has no field for it")
        void shouldOmitMimeWhenProviderHasNoField() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());
            FileAttachmentResolver.Spec brevo = spec(
                    "{\"fileAttachments\":{\"nameField\":\"name\",\"contentField\":\"content\"}}");

            @SuppressWarnings("unchecked")
            Map<String, Object> attachment = (Map<String, Object>) resolver.resolve(
                    "attachments", fileRef("t/a.txt", "a.txt", "text/plain"), brevo, "t").get(0);

            assertEquals(Map.of("name", "a.txt", "content", Base64.getEncoder().encodeToString("X".getBytes())),
                    attachment);
        }

        @Test
        @DisplayName("should fall back to a generic name and media type rather than send neither")
        void shouldFallBackOnIncompleteFileRef() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());
            // Recognised by its marker; a bare path with no name is deliberately
            // NOT a file any more (see shouldRequireNameOrTypeMarkerBesidePath).
            Map<String, Object> bare = new LinkedHashMap<>();
            bare.put("_type", "file");
            bare.put("path", "t/blob");

            @SuppressWarnings("unchecked")
            Map<String, Object> attachment = (Map<String, Object>) resolver.resolve(
                    "attachments", bare, sendgrid, "t").get(0);

            assertEquals("attachment", attachment.get("filename"));
            assertEquals("application/octet-stream", attachment.get("type"));
        }

        @Test
        @DisplayName("should refuse the call when the file cannot be read instead of sending without it")
        void shouldRefuseWhenFileIsGone() {
            when(storageClient.download(any(), any())).thenReturn(null);

            FileAttachmentException thrown = assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", fileRef("t/gone.pdf", "gone.pdf", "application/pdf"),
                            sendgrid, "t"));

            assertTrue(thrown.getMessage().contains("attachments"));
            assertTrue(thrown.getMessage().contains("could not be read"));
        }

        @Test
        @DisplayName("should refuse the call when the download throws")
        void shouldRefuseWhenDownloadThrows() {
            when(storageClient.download(any(), any())).thenThrow(new RuntimeException("403"));

            assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", fileRef("t/x.pdf", "x.pdf", null), sendgrid, "t"));
        }

        @Test
        @DisplayName("should refuse a batch over the endpoint ceiling")
        void shouldRefuseOversizedBatch() {
            FileAttachmentResolver.Spec small = spec(
                    "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"maxTotalBytes\":10}}");
            when(storageClient.download(any(), any())).thenReturn(new byte[20]);

            FileAttachmentException thrown = assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", fileRef("t/big.bin", "big.bin", null), small, "t"));

            assertTrue(thrown.getMessage().contains("add up to more than"));
        }

        @Test
        @DisplayName("should sum the batch rather than judge each file on its own")
        void shouldSumBatchAgainstCeiling() {
            FileAttachmentResolver.Spec small = spec(
                    "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"maxTotalBytes\":100}}");
            when(storageClient.download(any(), any())).thenReturn(new byte[60]);

            assertThrows(FileAttachmentException.class, () -> resolver.resolve("attachments",
                    List.of(fileRef("t/a", "a", null), fileRef("t/b", "b", null)), small, "t"));
        }

        @Test
        @DisplayName("should refuse when file storage is unavailable rather than send an empty attachment")
        void shouldRefuseWithoutStorageClient() {
            FileAttachmentResolver noStorage =
                    new FileAttachmentResolver(null, new ObjectMapper(), PLATFORM_MAX);

            assertThrows(FileAttachmentException.class, () -> noStorage.resolve("attachments",
                    fileRef("t/a.txt", "a.txt", null), sendgrid, "t"));
        }

        @Test
        @DisplayName("should measure the ceiling on the encoded payload, the way providers state it")
        void shouldMeasureCeilingOnEncodedSize() {
            // 600 raw bytes encode to 800. A ceiling of 700 is under the encoded
            // size and over the raw one: measured raw, this batch sailed past the
            // friendly refusal and into a provider 4xx.
            FileAttachmentResolver.Spec capped = spec(
                    "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"maxTotalBytes\":700}}");
            when(storageClient.download(any(), any())).thenReturn(new byte[600]);

            assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", fileRef("t/a.bin", "a.bin", null), capped, "t"));
        }

        @Test
        @DisplayName("should name the endpoint's own ceiling when that is what refused")
        void shouldBlameTheEndpointCeiling() {
            FileAttachmentResolver.Spec capped = spec(
                    "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"maxTotalBytes\":3000000}}");
            when(storageClient.download(any(), any())).thenReturn(new byte[3_000_000]);

            FileAttachmentException thrown = assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", fileRef("t/a.bin", "a.bin", null), capped, "t"));

            // Integer division answered a 3,000,000 byte ceiling with "2 MB", a
            // number smaller than what had just been accepted.
            assertTrue(thrown.getMessage().contains("2.8 MB"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("this endpoint accepts"), thrown.getMessage());
        }

        @Test
        @DisplayName("should name the platform ceiling as ours when the endpoint declared none")
        void shouldBlameThePlatformCeiling() {
            // Saying "this endpoint accepts 20 MB" told a Resend user their
            // endpoint was the limit, when Resend accepts 40 and we do not.
            when(storageClient.download(any(), any())).thenReturn(new byte[(int) PLATFORM_MAX]);

            FileAttachmentException thrown = assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", fileRef("t/a.bin", "a.bin", null), sendgrid, "t"));

            assertTrue(thrown.getMessage().contains("this workspace can send"), thrown.getMessage());
        }

        @Test
        @DisplayName("should read a file under the anonymous tenant rather than a null one when no tenant is known")
        void shouldFallBackToAnonymousTenant() {
            when(storageClient.download(any(), any())).thenReturn("X".getBytes());

            resolver.resolve("attachments", fileRef("t/a.txt", "a.txt", null), sendgrid, null);

            verify(storageClient).download(eq("anonymous"), eq("t/a.txt"));
        }

        @Test
        @DisplayName("should leave a bare string alone for the provider to judge")
        void shouldPassThroughBareString() {
            // Two shipped seeds carry example: ["item1","item2"], which teaches an
            // agent to send strings. This must not corrupt them into anything.
            assertEquals(List.of("item1"), resolver.resolve("attachments", List.of("item1"), sendgrid, "t"));
            verifyNoInteractions(storageClient);
        }

        @Test
        @DisplayName("should count a hand-built attachment against the ceiling too, not only the ones it resolved")
        void shouldCountPassThroughEntriesAgainstTheCeiling() {
            // A caller that already holds base64 (a file read back out of a
            // mailbox) reached the provider through the pass-through branch, which
            // accumulated nothing: the ceiling claimed to be enforced was not, for
            // any size, and the whole payload sat in this service's heap on the way.
            FileAttachmentResolver.Spec capped = spec(
                    "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"maxTotalBytes\":1000}}");
            Map<String, Object> handBuilt = new LinkedHashMap<>();
            handBuilt.put("filename", "huge.bin");
            handBuilt.put("content", "A".repeat(5000));

            assertThrows(FileAttachmentException.class, () ->
                    resolver.resolve("attachments", List.of(handBuilt), capped, "t"));
            verifyNoInteractions(storageClient);
        }

        @Test
        @DisplayName("should count a resolved file and a hand-built one against the same ceiling")
        void shouldSumMixedEntriesAgainstTheCeiling() {
            FileAttachmentResolver.Spec capped = spec(
                    "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"maxTotalBytes\":1000}}");
            when(storageClient.download(any(), any())).thenReturn(new byte[600]);
            Map<String, Object> handBuilt = new LinkedHashMap<>();
            handBuilt.put("filename", "b.bin");
            handBuilt.put("content", "A".repeat(400));

            // 600 raw encodes to 800, plus 400 hand-built, is over 1000.
            assertThrows(FileAttachmentException.class, () -> resolver.resolve("attachments",
                    List.of(fileRef("t/a.bin", "a.bin", null), handBuilt), capped, "t"));
        }

        @Test
        @DisplayName("should skip null entries in a list")
        void shouldSkipNullEntries() {
            List<Object> items = new java.util.ArrayList<>();
            items.add(null);

            assertTrue(resolver.resolve("attachments", items, sendgrid, "t").isEmpty());
        }
    }
}
