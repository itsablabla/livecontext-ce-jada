package com.apimarketplace.catalog.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the {@code bodyPath} an endpoint parameter declares in its
 * {@code api_tool_parameters.extras} blob.
 *
 * <p><b>Why anything needs this.</b> A parameter is addressed by two different
 * names depending on who is asking. The tool surface asks for {@code voice_id};
 * the request it produces carries that value at
 * {@code video_inputs[0].voice.voice_id}, because the parameter declares that
 * place as its {@code bodyPath}. A generation descriptor writes the SECOND
 * name, since it builds the provider's body directly. Anything matching a
 * descriptor's write path against the catalogue's parameter rows therefore has
 * to know both names, or it silently finds nothing: HeyGen's avatar and voice
 * lists are fetched with the caller's own key and were invisible on the
 * generation surface for exactly this reason, with no error anywhere.
 *
 * <p>Malformed or absent extras give {@code null}, never an exception: a
 * dropdown is worth no failed call.
 */
@Slf4j
public final class ParameterBodyPath {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParameterBodyPath() {}

    /**
     * The declared body path, or {@code null} when the parameter declares none.
     *
     * @param extras the raw {@code extras} JSON text stored on the parameter
     */
    public static String of(String extras) {
        if (extras == null || extras.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(extras).path("bodyPath");
            if (node.isMissingNode() || node.isNull()) return null;
            // TRIMMED, because the other side of every comparison is. A
            // descriptor's write paths are trimmed as they are parsed, so an
            // untrimmed "voice_id " here would never match one and the symptom
            // would be the silence this class exists to end.
            String path = node.asText().trim();
            return path.isEmpty() ? null : path;
        } catch (Exception e) {
            log.debug("[ParameterBodyPath] unreadable extras, treating as no bodyPath: {}", e.getMessage());
            return null;
        }
    }
}
