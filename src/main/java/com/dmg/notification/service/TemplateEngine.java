package com.dmg.notification.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple {{variable}} substitution engine.
 * Unknown variables are left as-is so templates can be partially rendered for testing.
 */
@Component
public class TemplateEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    public String render(String template, Map<String, String> variables) {
        if (template == null) return null;
        if (variables == null || variables.isEmpty()) return template;

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
