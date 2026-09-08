package com.apimarketplace.orchestrator.domain.file;

/**
 * Wording shared by every node that needs a file's BYTES and cannot get them.
 *
 * <p>Four nodes reach this situation - public_link, media, extract_from_file and the renderer
 * behind media - and they used to answer it four different ways, two of which blamed the author's
 * mapping for a value that was mapped correctly. One sentence, in one place, so the author reads
 * the same explanation wherever they hit it.
 */
public final class FileRefMessages {

    /**
     * A value that IS file-shaped but names no stored file. Prefix it with what could not be read,
     * e.g. {@code "The file " + NO_STORAGE_PATH}.
     */
    public static final String NO_STORAGE_PATH =
        "has no storage path, so its bytes cannot be read - this happens with a link to somewhere "
            + "else, or with a file this workspace knows only by id. Point it at a file the "
            + "workspace stores, such as the output of an upstream node that produced one.";

    private FileRefMessages() {
    }
}
