package com.ragproject.ragserver.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KeywordWindowExtractor {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9]{2,}");

    public List<String> extract(String question, int windowSize, int maxKeywords) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }

        int safeWindowSize = Math.max(windowSize, 2);
        int safeMaxKeywords = Math.max(maxKeywords, 1);

        Set<String> keywords = new LinkedHashSet<>();
        String trimmed = question.trim();

        Matcher matcher = TOKEN_PATTERN.matcher(trimmed);
        while (matcher.find() && keywords.size() < safeMaxKeywords) {
            String token = normalizeToken(matcher.group());
            if (token.length() >= 2) {
                keywords.add(token);
            }
        }

        String normalized = trimmed.replaceAll("\\s+", "");
        if (normalized.length() <= safeWindowSize) {
            keywords.add(normalizeToken(normalized));
        } else {
            for (int i = 0; i <= normalized.length() - safeWindowSize; i++) {
                if (keywords.size() >= safeMaxKeywords) {
                    break;
                }
                String window = normalizeToken(normalized.substring(i, i + safeWindowSize));
                if (window.length() >= 2) {
                    keywords.add(window);
                }
            }
        }

        List<String> result = new ArrayList<>();
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword)) {
                result.add(keyword);
            }
        }
        return result;
    }

    private String normalizeToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
