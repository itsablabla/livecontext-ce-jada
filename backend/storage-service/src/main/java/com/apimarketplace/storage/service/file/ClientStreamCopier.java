package com.apimarketplace.storage.service.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Copies a stored object to the client, telling a client that walked away apart
 * from a download that genuinely failed.
 *
 * <p><b>What this is for.</b> The three download endpoints each rethrew every
 * {@link IOException} from the copy, and labelled every one of them a client
 * disconnect. Both halves were wrong. {@code InputStream.transferTo} performs
 * the read from the stored object and the write to the client inside one try
 * block, and the two produce indistinguishable exceptions:
 * {@link DisconnectedClientHelper#isClientDisconnectedException} matches on type
 * and message text ({@code EOFException}, "broken pipe", "connection reset by
 * peer"), which is exactly what an S3 socket dying mid-read looks like. So a
 * server-side failure was counted as a disconnect, and the caller could receive
 * a truncated body under a 200 with the full advertised {@code Content-Length}.
 *
 * <p><b>What it does.</b> Writes go through a wrapper that records which side
 * failed, and only a write-side failure that the helper also recognises is
 * treated as the client hanging up: swallowed, counted as a disconnect, logged
 * at debug. Everything else propagates and is counted as a stream error.
 *
 * <p><b>What it does NOT do.</b> Remove the container's error dispatch on an
 * aborted download. Spring's {@code StreamingResponseBodyTask} calls
 * {@code flush()} after {@code writeTo} returns, and on a response already in
 * its error state that flush throws again, so the dispatch happens whether or
 * not this class rethrows. The {@code MimeHeaders} noise those aborts produce
 * lives in the container's own handling and is not addressed here.
 */
public final class ClientStreamCopier {

    private static final Logger log = LoggerFactory.getLogger(ClientStreamCopier.class);

    private ClientStreamCopier() {
    }

    /**
     * Stream {@code source} to {@code out}, closing the source and recording
     * metrics either way.
     *
     * @param context short identifier for the log line (an object key, an id)
     * @throws IOException only when the failure is the server's, never when the
     *                     client hung up
     */
    public static void copy(DownloadStream source,
                            OutputStream out,
                            long advertisedLength,
                            StorageStreamingMetrics metrics,
                            String context) throws IOException {
        WriteTagging tagged = new WriteTagging(out);
        try (StorageStreamingMetrics.StreamSpan span = metrics.startStream();
             DownloadStream s = source) {
            try {
                s.stream().transferTo(tagged);
                if (advertisedLength > 0) {
                    metrics.recordBytes(advertisedLength);
                }
            } catch (IOException e) {
                if (tagged.failed && DisconnectedClientHelper.isClientDisconnectedException(e)) {
                    // Nobody is left to receive an error. Returning normally lets
                    // the container finish the aborted response quietly.
                    metrics.recordClientDisconnect();
                    log.debug("Client disconnected mid-stream: {} ({})", context, e.getMessage());
                    return;
                }
                metrics.recordStreamError();
                log.warn("Download failed while {} {}: {}",
                        tagged.failed ? "writing" : "reading", context, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                metrics.recordStreamError();
                throw e;
            }
        }
    }

    /**
     * Records that a failure came from writing to the client rather than from
     * reading the stored object. Both happen inside a single
     * {@code transferTo}, and their exceptions are indistinguishable by type or
     * message, so the side has to be captured where it occurs.
     */
    private static final class WriteTagging extends OutputStream {

        private final OutputStream delegate;
        private boolean failed;

        private WriteTagging(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                delegate.write(b);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                delegate.write(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                delegate.flush();
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void close() {
            // The container owns the response stream; closing it here would take
            // it away from whoever still has to finish the response.
        }
    }
}
