package com.apimarketplace.catalog.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * CE-side HTTP client that fetches the currently active API-catalog bundle
 * from the cloud ({@code GET {api-catalog.bundle.cloud-url}/api/catalog/public/bundles/latest}).
 * Mirrors {@code agent-service CatalogBundleFetcher}.
 *
 * <p>Returns a structured {@link FetchResult} so the scheduler can persist the
 * reason on {@code api_catalog_bundle_sync_status} without catching exceptions:
 * <ul>
 *   <li>{@code FETCHED}: cloud returned 200 with a parseable bundle.</li>
 *   <li>{@code NO_ACTIVE}: cloud returned 404 (no bundle activated yet) - not
 *       a failure, just "keep whatever CE already has".</li>
 *   <li>{@code NOT_MODIFIED}: cloud returned 304 because the bundle this install
 *       already holds is still the active one. The steady-state outcome, and the
 *       reason the poll costs nothing instead of transferring ~32 MB.</li>
 *   <li>{@code HTTP_ERROR}: any other 4xx/5xx.</li>
 *   <li>{@code NETWORK_ERROR}: connection refused, timeout, DNS, etc.</li>
 *   <li>{@code NOT_CONFIGURED}: {@code api-catalog.bundle.cloud-url} empty.</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiCatalogBundleFetcher {

    private final RestTemplate restTemplate;
    private final String cloudUrl;

    public ApiCatalogBundleFetcher(
            RestTemplate restTemplate,
            @Value("${api-catalog.bundle.cloud-url:}") String cloudUrl) {
        this.restTemplate = restTemplate;
        this.cloudUrl = cloudUrl == null ? "" : cloudUrl.trim();
    }

    public record FetchResult(Status status, ApiCatalogSignedBundle bundle, String detail) {
        public static FetchResult fetched(ApiCatalogSignedBundle b) { return new FetchResult(Status.FETCHED, b, null); }
        public static FetchResult noActive()                        { return new FetchResult(Status.NO_ACTIVE, null, null); }
        public static FetchResult notModified()                     { return new FetchResult(Status.NOT_MODIFIED, null, null); }
        public static FetchResult notConfigured()                   { return new FetchResult(Status.NOT_CONFIGURED, null, "api-catalog.bundle.cloud-url is empty"); }
        public static FetchResult httpError(String detail)          { return new FetchResult(Status.HTTP_ERROR, null, detail); }
        public static FetchResult networkError(String d)            { return new FetchResult(Status.NETWORK_ERROR, null, d); }
    }

    public enum Status { FETCHED, NO_ACTIVE, NOT_MODIFIED, NOT_CONFIGURED, HTTP_ERROR, NETWORK_ERROR }

    /**
     * Fetch the cloud's active bundle, skipping the transfer when this install
     * already holds it.
     *
     * @param knownChecksum checksum of the bundle currently applied here, sent as
     *                      {@code If-None-Match}. An unchanged bundle then comes
     *                      back as a bodiless 304 instead of ~32 MB of JSON.
     *                      Null or blank (a first sync) simply fetches.
     */
    public FetchResult fetchLatest(String knownChecksum) {
        if (cloudUrl.isEmpty()) return FetchResult.notConfigured();
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/catalog/public/bundles/latest";
        try {
            HttpHeaders headers = new HttpHeaders();
            if (knownChecksum != null && !knownChecksum.isBlank()) {
                headers.setIfNoneMatch("\"" + knownChecksum + "\"");
            }
            ResponseEntity<ApiCatalogSignedBundle> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), ApiCatalogSignedBundle.class);
            if (resp.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return FetchResult.notModified();
            }
            ApiCatalogSignedBundle body = resp.getBody();
            if (body == null) {
                return FetchResult.httpError("cloud returned 200 with empty body");
            }
            return FetchResult.fetched(body);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                // Some client stacks surface a 304 as an exception rather than a
                // response. It is a success either way, never an error.
                return FetchResult.notModified();
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // Cloud has no active bundle - not an error, nothing to apply yet.
                return FetchResult.noActive();
            }
            return FetchResult.httpError("HTTP " + e.getStatusCode().value() + " from " + url);
        } catch (RestClientException e) {
            return FetchResult.networkError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
