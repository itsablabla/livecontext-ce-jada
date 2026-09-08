package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.dto.PublicIntegrationDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationDetailDTO;
import com.apimarketplace.catalog.service.PublicIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The integration catalog as the PUBLIC website reads it: the landing section, the
 * /integrations directory and each /integrations/{slug} page.
 *
 * <p>Anonymous by design. It is allow-listed at the gateway, so nothing here may depend
 * on {@code X-User-ID}: every read goes through {@link PublicIntegrationService}, which
 * serves only integrations marked {@code visibility = 'public'}.
 *
 * <p>GET only, deliberately. The gateway's public allow-list is method-agnostic (it
 * matches on path, not verb), so a route placed on it is reachable by any verb; the
 * safety comes from this class declaring no other mapping, exactly like the
 * {@code /api/publications/by-slug} sibling. Do not add a write mapping under this
 * prefix - put it behind the authenticated controllers instead.
 */
@RestController
@RequestMapping("/api/public/integrations")
@RequiredArgsConstructor
@Slf4j
public class PublicIntegrationController {

    private final PublicIntegrationService publicIntegrationService;

    /** Default page size, sized for one screen of the /integrations grid. */
    private static final int DEFAULT_PAGE_SIZE = 60;

    /**
     * One page of public integrations, most-run first.
     *
     * @param q optional case-insensitive search over name, slug and description
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "q", required = false) String q) {
        try {
            List<PublicIntegrationDTO> content = publicIntegrationService.list(page, size, q);
            int total = publicIntegrationService.count(q);
            return ResponseEntity.ok(pageBody(content, total, page, PublicIntegrationService.clampSize(size)));
        } catch (Exception e) {
            // A marketing page must degrade, not 500: the section renders nothing and the
            // rest of the page is unaffected. Logged at WARN because an empty public
            // directory is a visible regression even though it is not an outage.
            log.warn("Public integration listing failed, serving an empty page: {}", e.getMessage(), e);
            return ResponseEntity.ok(pageBody(List.of(), 0, page, PublicIntegrationService.clampSize(size)));
        }
    }

    /**
     * One public integration with its endpoint list.
     *
     * <p>404 covers "no such slug", "private", "retired" and "no active endpoint"
     * indistinguishably: telling them apart would let anyone probe whether a privately
     * registered integration exists. A failure to READ, by contrast, answers 500 - a
     * 404 there would invite search engines to drop a page that still exists.
     */
    @GetMapping("/{slug}")
    public ResponseEntity<PublicIntegrationDetailDTO> bySlug(@PathVariable String slug) {
        try {
            Optional<PublicIntegrationDetailDTO> detail = publicIntegrationService.findBySlug(slug);
            return detail.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Public integration read failed for slug {}: {}", slug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * The page envelope, matching the shape the other catalog list endpoints already
     * return so the frontend readers stay interchangeable.
     */
    private static Map<String, Object> pageBody(
            List<PublicIntegrationDTO> content, int total, int page, int size) {
        int totalPages = total > 0 ? (int) Math.ceil((double) total / size) : 0;
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        body.put("totalElements", total);
        body.put("totalPages", totalPages);
        body.put("page", page);
        body.put("size", size);
        body.put("first", page == 0);
        body.put("last", page >= totalPages - 1);
        body.put("numberOfElements", content.size());
        return body;
    }
}
