package com.apimarketplace.agent.tools.ask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The person's answer to one {@link UserQuestion}, in the form the agent reads back.
 *
 * @param header   the question's header, which is how the agent matches answer to question
 * @param selected the option labels chosen; one shape for single and multi select, so the
 *                 agent always reads a list. Empty when the person only typed their own words
 * @param freeText what the person typed instead of, or in addition to, picking an option;
 *                 {@code null} when they only picked
 * @param custom   true when the answer came from the free-text field rather than the options
 */
public record UserQuestionAnswer(String header, List<String> selected, String freeText, boolean custom) {

    public UserQuestionAnswer {
        selected = selected == null ? List.of() : List.copyOf(selected);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("header", header);
        map.put("selected", selected);
        map.put("freeText", freeText);
        map.put("custom", custom);
        return map;
    }
}
