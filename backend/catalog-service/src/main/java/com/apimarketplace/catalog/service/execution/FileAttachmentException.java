package com.apimarketplace.catalog.service.execution;

/**
 * Raised when an attachment the caller supplied cannot be turned into what the
 * provider wants: the bytes are gone, or the batch is larger than the endpoint
 * accepts.
 *
 * <p>It is a distinct type because {@code HttpExecutionService.prepareRequestBody}
 * catches everything else and returns a null body. Swallowed there, a dropped
 * attachment would go out as a mail that SENDS with the file silently missing,
 * and nobody is told. This one has to travel out of that catch.
 *
 * <p>The message is worded for whoever supplied the file, since that is who can
 * supply a different one.
 */
public class FileAttachmentException extends RuntimeException {
    public FileAttachmentException(String message) {
        super(message);
    }
}
