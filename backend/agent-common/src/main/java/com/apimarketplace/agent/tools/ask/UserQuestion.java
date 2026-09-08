package com.apimarketplace.agent.tools.ask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One question an agent puts to the person it is talking to, in a channel-agnostic form.
 *
 * <p>The chat card is one renderer of this shape; a later Telegram or Slack channel renders
 * the same record. Nothing here knows about streams, cards or Redis.
 *
 * @param header      short label naming the topic of the question (a tab title, a column header)
 * @param question    the full question text
 * @param options     the choices offered, 2 to 4 of them; the person can always answer in
 *                    their own words instead, so an "Other" option is never declared here
 * @param multiSelect whether several options may be chosen at once
 */
public record UserQuestion(String header, String question, List<UserQuestionOption> options,
                           boolean multiSelect) {

    public UserQuestion {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Wire form, the same on the card event, the pending action and the tool result. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("header", header);
        map.put("question", question);
        map.put("options", options.stream().map(UserQuestionOption::toMap).toList());
        map.put("multiSelect", multiSelect);
        return map;
    }
}
