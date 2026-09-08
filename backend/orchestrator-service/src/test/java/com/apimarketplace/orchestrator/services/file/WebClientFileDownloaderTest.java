package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.orchestrator.services.file.FileDownloader.FileDownloadException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebClientFileDownloader")
class WebClientFileDownloaderTest {

    private HttpServer server;
    private AtomicInteger targetHits;
    private final AtomicReference<String> receivedRawQuery = new AtomicReference<>();
    private WebClientFileDownloader downloader;

    @BeforeEach
    void setUp() throws Exception {
        targetHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            byte[] body = "target".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/presigned", exchange -> {
            receivedRawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "clip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        downloader = new WebClientFileDownloader(WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * The download budget is deliberately generous: nothing here asserts latency,
     * only that a 302 is rejected and {@code /target} is never fetched. A tight
     * budget turns the first WebClient call in the JVM (Netty event loop, resolver
     * and connection-pool bootstrap) into the thing under test - with 5 s this
     * failed on a loaded machine with "Download timeout after 5s" instead of the
     * 302, and passed with ~50 ms to spare on the very next run. A redirect that IS
     * rejected comes back in milliseconds, so a larger ceiling costs nothing and
     * only changes how long we wait before calling it a failure.
     */
    @Test
    @DisplayName("should reject redirects without following them")
    void shouldRejectRedirectsWithoutFollowingThem() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

        assertThatThrownBy(() -> downloader.download(url, Duration.ofSeconds(60)))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("302");
        assertThat(targetHits).hasValue(0);
    }

    /**
     * Regression for the download that reached BytePlus TOS as
     * "400 AuthorizationHeaderMalformed" while the very same URL served the clip
     * fine through curl. WebClient.uri(String) hands the value to the
     * UriBuilderFactory, which re-encodes an already-encoded query: the '%' of a
     * '%2F' becomes '%25', so the signed credential shipped as '%252F' and the
     * signature no longer matched. Every provider-presigned URL carries '%2F' in
     * its credential (TOS X-Tos-Credential, S3 X-Amz-Credential, Azure SAS, GCS),
     * so this broke the whole class, not one provider.
     *
     * The assertion is on what the SERVER received, not on what the client was
     * given: that is the only place the difference is observable, and it fails on
     * the pre-fix code with '%252F'.
     */
    @Test
    @DisplayName("should send a presigned query verbatim, never re-encoding its escapes")
    void shouldSendPresignedQueryVerbatim() throws Exception {
        String rawQuery = "X-Tos-Algorithm=TOS4-HMAC-SHA256"
                + "&X-Tos-Credential=AKLT%2F20260905%2Fap-southeast-1%2Ftos%2Frequest"
                + "&X-Tos-Signature=a6a5d7fc53ea64fff95f9a2a5880f38173ee9f348335fc7a3199635f8ddd2234";
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/presigned?" + rawQuery;

        byte[] content = downloader.download(url, Duration.ofSeconds(60));

        assertThat(content).isEqualTo("clip".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedRawQuery.get())
                .as("the presigned query must arrive byte-identical, or the provider signature breaks")
                .isEqualTo(rawQuery);
        assertThat(receivedRawQuery.get()).doesNotContain("%252F");
    }
}
