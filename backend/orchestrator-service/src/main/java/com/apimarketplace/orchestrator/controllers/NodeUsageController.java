package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.orchestrator.services.usage.NodeUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reads the platform-wide node-usage ranking (V461).
 *
 * <p>Serves the ORDER and nothing else. The counters behind it are cross-tenant sums
 * that no account owns, so the ranking is safe to show any signed-in builder while the
 * volumes stay private - and every consumer so far only ever needed the order.
 */
@RestController
@RequestMapping("/api/node-usage")
public class NodeUsageController {

    private static final Logger log = LoggerFactory.getLogger(NodeUsageController.class);

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final NodeUsageRepository nodeUsageRepository;

    public NodeUsageController(NodeUsageRepository nodeUsageRepository) {
        this.nodeUsageRepository = nodeUsageRepository;
    }

    /**
     * GET /api/node-usage/ranking - node type keys, most-run first.
     *
     * <p>Answers with an empty list rather than an error when the ledger cannot be read:
     * a caller ranking a list it can already render must degrade to its own default
     * order, not to a broken screen.
     */
    @GetMapping("/ranking")
    public ResponseEntity<List<String>> getRanking(
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        try {
            return ResponseEntity.ok(nodeUsageRepository.findRanking(bounded));
        } catch (Exception e) {
            log.warn("Node usage ranking unavailable: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }
}
