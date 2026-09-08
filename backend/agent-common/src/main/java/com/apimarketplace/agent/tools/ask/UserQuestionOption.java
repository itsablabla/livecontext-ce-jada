package com.apimarketplace.agent.tools.ask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One choice offered by a {@link UserQuestion}.
 *
 * @param label       what the person picks; also what the agent reads back as {@code selected}
 * @param description one line saying what choosing it means, or {@code null}
 */
public record UserQuestionOption(String label, String description) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        if (description != null && !description.isBlank()) {
            map.put("description", description);
        }
        return map;
    }
}
