package com.apimarketplace.storage.service.file;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property that matters here is the CLASSIFICATION, not the swallowing.
 *
 * <p>Before, every {@link IOException} from the copy was labelled a client
 * disconnect. Read and write happen inside one {@code transferTo}, and an S3
 * socket dying mid-read produces exactly the shapes
 * {@code DisconnectedClientHelper} matches ({@code EOFException}, "connection
 * reset by peer"), so a server-side failure could be reported as a hang-up and
 * the caller handed a truncated body under a 200. These tests pin that a
 * read-side failure still propagates even when it wears a disconnect's clothes.
 */
@DisplayName("ClientStreamCopier - a client hanging up is not a server error")
class ClientStreamCopierTest {

    private final StorageStreamingMetrics metrics = new StorageStreamingMetrics(new SimpleMeterRegistry());

    private static DownloadStream source(byte[] bytes) {
        return new DownloadStream(new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    /** An output that fails the way Tomcat does once the peer is gone. */
    private static OutputStream brokenPipeAfter(int bytes) {
        return new OutputStream() {
            private int written;
            @Override public void write(int b) throws IOException {
                if (++written > bytes) throw new IOException("Broken pipe");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > bytes) throw new IOException("Broken pipe");
            }
        };
    }

    @Test
    @DisplayName("A complete copy writes every byte and records them")
    void copiesFully() throws Exception {
        byte[] payload = "the stored object".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ClientStreamCopier.copy(source(payload), out, payload.length, metrics, "ctx");

        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    @DisplayName("A client that hangs up mid-body returns normally: there is nobody left to send an error to")
    void clientDisconnectIsSwallowed() {
        byte[] payload = new byte[8192];

        assertThatCode(() -> ClientStreamCopier.copy(
                source(payload), brokenPipeAfter(64), payload.length, metrics, "ctx"))
                .as("a write-side failure the helper recognises is the client leaving, not a fault to raise")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An S3 socket dying mid-read propagates even though it LOOKS exactly like a client disconnect")
    void serverSideReadFailurePropagates() {
        // The trap this guards: DisconnectedClientHelper classifies on type and
        // message text, and "Connection reset by peer" is the ordinary shape of a
        // dead S3 socket on Linux. Classifying by message alone would swallow it
        // and hand the caller a truncated body under a 200 with the full
        // Content-Length. Only a WRITE-side failure may be treated as a hang-up.
        InputStream unreadable = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("Connection reset by peer");
            }
        };
        DownloadStream ds = new DownloadStream(unreadable, 100, "application/octet-stream");

        assertThatThrownBy(() -> ClientStreamCopier.copy(ds, new ByteArrayOutputStream(), 100, metrics, "ctx"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Connection reset by peer");
    }

    @Test
    @DisplayName("An EOFException from the stored object is a short read, not a hang-up")
    void serverSideEofPropagates() {
        InputStream truncated = new InputStream() {
            @Override public int read() throws IOException {
                throw new java.io.EOFException();
            }
        };

        assertThatThrownBy(() -> ClientStreamCopier.copy(
                new DownloadStream(truncated, 100, "application/octet-stream"),
                new ByteArrayOutputStream(), 100, metrics, "ctx"))
                .isInstanceOf(java.io.EOFException.class);
    }

    @Test
    @DisplayName("The two outcomes are counted apart: a hang-up is a disconnect, a read failure is an error")
    void metricsDistinguishTheTwoOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StorageStreamingMetrics m = new StorageStreamingMetrics(registry);

        assertThatCode(() -> ClientStreamCopier.copy(
                source(new byte[8192]), brokenPipeAfter(64), 8192, m, "ctx")).doesNotThrowAnyException();
        assertThat(counter(registry, "disconnect")).isEqualTo(1.0);
        assertThat(counter(registry, "error")).isZero();

        InputStream unreadable = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Connection reset by peer"); }
        };
        assertThatThrownBy(() -> ClientStreamCopier.copy(
                new DownloadStream(unreadable, 1, "application/octet-stream"),
                new ByteArrayOutputStream(), 1, m, "ctx")).isInstanceOf(IOException.class);
        assertThat(counter(registry, "error")).isEqualTo(1.0);
    }

    /** Sum of the counters whose name mentions the given outcome. */
    private static double counter(SimpleMeterRegistry registry, String outcome) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().contains(outcome)
                        || String.valueOf(meter.getId().getTags()).contains(outcome))
                .filter(meter -> meter instanceof io.micrometer.core.instrument.Counter)
                .mapToDouble(meter -> ((io.micrometer.core.instrument.Counter) meter).count())
                .sum();
    }

    @Test
    @DisplayName("The source stream is closed on every path, including a disconnect")
    void alwaysClosesTheSource() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream tracked = new ByteArrayInputStream(new byte[4096]) {
            @Override public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        ClientStreamCopier.copy(new DownloadStream(tracked, 4096, "application/octet-stream"),
                brokenPipeAfter(16), 4096, metrics, "ctx");

        assertThat(closed).as("a leaked S3 connection is the other way this goes wrong").isTrue();
    }

    @Test
    @DisplayName("A runtime failure is not mistaken for a disconnect")
    void runtimeFailurePropagates() {
        InputStream boom = new InputStream() {
            @Override public int read() {
                throw new IllegalStateException("decryption failed");
            }
        };

        assertThatThrownBy(() -> ClientStreamCopier.copy(
                new DownloadStream(boom, 10, "application/octet-stream"),
                new ByteArrayOutputStream(), 10, metrics, "ctx"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A write failure that is NOT a hang-up still propagates: a full disk is a server fault")
    void nonDisconnectWriteFailurePropagates() {
        // Tagging the write side is not enough on its own: only a write failure
        // the helper recognises may be swallowed.
        OutputStream fullDisk = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("No space left on device");
            }
        };

        assertThatThrownBy(() -> ClientStreamCopier.copy(
                source(new byte[1024]), fullDisk, 1024, metrics, "ctx"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No space left on device");
    }
}
