package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the files a caller attached into the object one provider wants inside a
 * JSON body.
 *
 * <p>Every surface of the platform hands a file over as a FileRef
 * ({@code {_type:"file", path, name, mimeType, size}}): the output of a download
 * step, a file an agent already has, a file a user picked. No mail provider has
 * ever heard of one. They all want the same three things, a name, the bytes as
 * base64, a media type, under names none of them agree on ({@code content} vs
 * {@code Content} vs {@code data}), so the descriptor names the fields and this
 * class does the conversion once for all of them.
 *
 * <p>Before this existed, an attachment could not be sent at all on a JSON body:
 * the seed had to ask the caller for base64 it had no way to produce, so the
 * parameter existed and was unusable. See SCHEMA.md "fileAttachments".
 *
 * <p><b>It refuses loudly.</b> A file that is gone, a batch over the endpoint's
 * ceiling: both stop the call. The alternative is the failure this replaces, a
 * mail that sends with the attachment quietly missing.
 */
@Slf4j
@Component
public class FileAttachmentResolver {

    /** The field every FileRef carries, and the only one needed to find the bytes. */
    private static final String STORAGE_KEY = "path";

    /**
     * Field names an attachment object may use for the file name, the encoded bytes
     * and the media type, in priority order.
     *
     * <p>The descriptor writes the FIRST of each triple, so a file resolved here
     * always matches. The rest exist for an agent that built the object by hand
     * from a provider's own documentation.
     *
     * <p><b>They live here, not beside the MIME writer that reads them.</b> While
     * the writer accepted four spellings of the content field and the ceiling
     * counted only the descriptor's own, an attachment sent under any other
     * spelling was written to the wire and counted as zero: the limit was one
     * field name away from not existing.
     */
    public static final List<String> NAME_FIELDS = List.of("filename", "fileName", "name", "Name");
    public static final List<String> CONTENT_FIELDS = List.of("content", "Content", "data", "contentBytes");
    public static final List<String> MIME_FIELDS = List.of("type", "mimeType", "contentType", "ContentType", "content_type");

    private final StorageClient storageClient;
    private final ObjectMapper objectMapper;
    private final long defaultMaxTotalBytes;

    public FileAttachmentResolver(@Autowired(required = false) StorageClient storageClient,
                                  ObjectMapper objectMapper,
                                  @Value("${catalog.attachments.max-total-bytes:20971520}") long defaultMaxTotalBytes) {
        this.storageClient = storageClient;
        this.objectMapper = objectMapper;
        this.defaultMaxTotalBytes = defaultMaxTotalBytes;
    }

    /**
     * How one endpoint spells an attachment, read from the parameter's
     * {@code extras.fileAttachments}.
     *
     * @param nameField      provider field carrying the file name (required)
     * @param contentField   provider field carrying the encoded bytes (required)
     * @param mimeField      provider field carrying the media type, null when the provider has none
     * @param dataUrl        true when the bytes go out as {@code data:<mime>;base64,<bytes>} rather than bare base64
     * @param constantFields   fields every attachment object must carry verbatim (Graph's {@code @odata.type})
     * @param maxTotalBytes     ceiling on the summed ENCODED size of one call's attachments, which is
     *                          the way providers state their limits
     * @param cappedByPlatform  true when the platform ceiling is lower than what the endpoint declared,
     *                          so a refusal can name the limit that actually refused rather than blame
     *                          the provider for a number that is ours
     */
    public record Spec(String nameField,
                       String contentField,
                       String mimeField,
                       boolean dataUrl,
                       Map<String, String> constantFields,
                       long maxTotalBytes,
                       boolean cappedByPlatform) {}

    /**
     * Read the descriptor off a parameter's extras JSON.
     *
     * @return null when the parameter declares none, which is every parameter but the attachment ones
     */
    public Spec parseSpec(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank() || "{}".equals(extrasJson)) return null;
        try {
            JsonNode node = objectMapper.readTree(extrasJson).path("fileAttachments");
            if (!node.isObject()) return null;

            String nameField = node.path("nameField").asText(null);
            String contentField = node.path("contentField").asText(null);
            if (nameField == null || nameField.isBlank() || contentField == null || contentField.isBlank()) {
                log.warn("Ignoring fileAttachments descriptor without nameField/contentField: {}", node);
                return null;
            }
            String mimeField = node.path("mimeField").asText(null);
            boolean dataUrl = "data_url".equals(node.path("encoding").asText("base64"));

            Map<String, String> constants = new LinkedHashMap<>();
            JsonNode constantsNode = node.path("constantFields");
            if (constantsNode.isObject()) {
                constantsNode.fields().forEachRemaining(e -> constants.put(e.getKey(), e.getValue().asText()));
            }

            long declaredMax = node.path("maxTotalBytes").asLong(0L);
            long max = declaredMax > 0 ? Math.min(declaredMax, defaultMaxTotalBytes) : defaultMaxTotalBytes;
            return new Spec(nameField, contentField,
                    (mimeField == null || mimeField.isBlank()) ? null : mimeField,
                    dataUrl, Map.copyOf(constants), max,
                    declaredMax <= 0 || declaredMax > defaultMaxTotalBytes);
        } catch (Exception e) {
            log.warn("Ignoring malformed fileAttachments descriptor: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Rewrite one parameter's value into the provider's attachment objects.
     *
     * <p>A single file and a list of them are both accepted, because a workflow
     * mapping one upstream file into the field is at least as common as a list.
     * The result is always a list, which is the shape every provider declares.
     *
     * <p>An entry that is NOT a FileRef passes through untouched: an agent that
     * already holds base64 can still build the provider object by hand, and this
     * class must not corrupt it.
     *
     * @param paramName the caller-facing parameter name, quoted in every error
     * @param tenantId  owner whose storage the bytes are read from
     */
    public List<Object> resolve(String paramName, Object value, Spec spec, String tenantId) {
        List<Object> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            items.addAll(list);
        } else if (value != null) {
            items.add(value);
        }

        List<Object> resolved = new ArrayList<>(items.size());
        long encodedBytes = 0;
        for (Object item : items) {
            if (item == null) continue;

            String storageKey = storageKeyOf(item);
            Object outgoing;
            if (storageKey == null) {
                // Not a file handle. Either a provider object the caller built
                // itself (legitimate) or a bare string, which this cannot tell
                // apart from the first, so it goes out and the provider judges it.
                outgoing = item;
            } else {
                byte[] bytes = download(paramName, storageKey, tenantId);
                @SuppressWarnings("unchecked")
                Map<String, Object> fileRef = (Map<String, Object>) item;
                outgoing = buildAttachment(fileRef, bytes, spec);
            }

            // Measured on the ENCODED payload, because that is how every provider
            // states its limit ("40MB total after encoding"). Base64 inflates by a
            // third, so summing raw bytes let a batch a third over the provider's
            // limit through the friendly refusal and into a raw 4xx.
            //
            // Counted for a pass-through entry TOO. A caller that already holds
            // base64 (a file read back out of a mailbox, say) could otherwise send
            // any size it liked past a ceiling that claimed to be enforced, and
            // hold the whole thing in this service's heap on the way.
            encodedBytes += encodedSizeOf(outgoing, spec);
            if (encodedBytes > spec.maxTotalBytes()) {
                throw new FileAttachmentException(describeCeiling(paramName, spec));
            }
            resolved.add(outgoing);
        }
        return resolved;
    }

    /**
     * How much of the ceiling one outgoing attachment uses.
     *
     * <p>For an object, the encoded bytes it carries under the provider's own
     * content field; for anything else, its own length, which is what will be
     * serialised. Either way it is what goes on the wire.
     */
    private long encodedSizeOf(Object outgoing, Spec spec) {
        if (outgoing instanceof Map<?, ?> map) {
            List<String> candidates = new ArrayList<>();
            candidates.add(spec.contentField());
            candidates.addAll(CONTENT_FIELDS);
            String content = firstNonBlank(map, candidates);
            return content == null ? 0 : content.length();
        }
        return String.valueOf(outgoing).length();
    }

    /**
     * The first of these fields the object actually fills, or null.
     *
     * <p><b>One function, called by both halves.</b> The ceiling here and the MIME
     * writer that decides what to send have to pick the SAME field, and while they
     * were two functions they picked differently: this one stopped at the first
     * non-null while the writer skipped blanks, so an object carrying
     * {@code content:""} beside a full {@code data:} counted as nothing and was
     * written in full. Sharing the field names was not enough; they have to share
     * the choosing.
     */
    public static String firstNonBlank(Map<?, ?> map, List<String> candidates) {
        for (String candidate : candidates) {
            Object value = map.get(candidate);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * Why the batch was refused, naming the cap that actually refused it.
     *
     * <p>Two different limits reach here: the endpoint's own, declared by the
     * seed from the provider's documentation, and the platform's. Saying "this
     * endpoint" for the platform's told a Resend user their endpoint accepts
     * 20 MB when it accepts 40. And the size is formatted rather than divided:
     * integer division answered a 3,000,000 byte ceiling with "2 MB", so the
     * reader was told a number smaller than what had just been accepted.
     */
    private String describeCeiling(String paramName, Spec spec) {
        String whose = spec.cappedByPlatform()
                ? "which is the most this workspace can send in one call"
                : "which is the most this endpoint accepts";
        return "The files attached to '" + paramName + "' add up to more than "
                + formatMegabytes(spec.maxTotalBytes()) + " once encoded, " + whose
                + ". Send fewer files, or smaller ones, or share a link instead.";
    }

    /** One decimal place, so a limit under a megabyte never reads as "0 MB". */
    private static String formatMegabytes(long bytes) {
        return new java.math.BigDecimal(bytes)
                .divide(new java.math.BigDecimal(1024 * 1024), 1, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + " MB";
    }

    private Map<String, Object> buildAttachment(Map<String, Object> fileRef, byte[] bytes, Spec spec) {
        String name = asText(fileRef.get("name"));
        if (name == null || name.isBlank()) name = "attachment";
        String mimeType = asText(fileRef.get("mimeType"));
        if (mimeType == null || mimeType.isBlank()) mimeType = "application/octet-stream";

        String encoded = Base64.getEncoder().encodeToString(bytes);
        if (spec.dataUrl()) {
            encoded = "data:" + mimeType + ";base64," + encoded;
        }

        Map<String, Object> attachment = new LinkedHashMap<>(spec.constantFields());
        attachment.put(spec.nameField(), name);
        attachment.put(spec.contentField(), encoded);
        if (spec.mimeField() != null) {
            attachment.put(spec.mimeField(), mimeType);
        }
        return attachment;
    }

    private byte[] download(String paramName, String storageKey, String tenantId) {
        if (storageClient == null) {
            throw new FileAttachmentException("Attachments are unavailable on this install: file storage is not reachable.");
        }
        String tenant = (tenantId == null || tenantId.isBlank()) ? "anonymous" : tenantId;
        byte[] bytes;
        try {
            bytes = storageClient.download(tenant, storageKey);
        } catch (Exception e) {
            log.warn("Attachment download failed for key={}: {}", storageKey, e.getMessage());
            bytes = null;
        }
        if (bytes == null || bytes.length == 0) {
            throw new FileAttachmentException("The file given for '" + paramName + "' could not be read. "
                    + "It may have been deleted, or belong to another workspace. Attach a file a step in this run produced.");
        }
        return bytes;
    }

    /**
     * The storage key of a FileRef, or null when the value is not one.
     *
     * <p>Recognised the way {@code MultipartBodyEncoder.coerceToFileRef} does:
     * the {@code _type:"file"} marker, or a {@code path} paired with a
     * {@code name}. The marker alone is not required because an output that has
     * been through a template round-trip keeps the two fields and does not
     * always keep the marker.
     *
     * <p><b>A bare {@code path} is NOT enough</b>, which is the whole reason this
     * is not one line. Resend documents an attachment as
     * {@code {filename, path}} where {@code path} is a remote URL it fetches
     * itself. Treating that as a storage key made the platform look for a file
     * under a key that is a URL, fail, and refuse the entire mail with a message
     * blaming a deleted file. A URL is rejected outright for the same reason: a
     * storage key is never absolute.
     */
    private String storageKeyOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Object path = map.get(STORAGE_KEY);
        if (!(path instanceof String key) || key.isBlank()) return null;

        boolean markedAsFile = "file".equals(asText(map.get("_type")));
        boolean hasName = asText(map.get("name")) != null && !asText(map.get("name")).isBlank();
        if (!markedAsFile && !hasName) return null;

        if (ABSOLUTE_URL.matcher(key).find()) return null;
        return key;
    }

    /** {@code scheme://} at the head of a value: a link the provider fetches, never a storage key. */
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
