package com.poolguard.service;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

@Component
public class AnswerEvaluator {
    /** 标准答案默认精确匹配；keywords: 后每行一个必需关键词。 */
    public boolean passes(String answer, String expected) {
        if (answer == null || answer.isBlank() || expected == null || expected.isBlank()) return false;
        if (expected.startsWith("keywords:")) {
            var words = expected.substring(9).lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
            return !words.isEmpty() && words.stream().allMatch(word -> normalize(answer).contains(normalize(word)));
        }
        return normalize(answer).equals(normalize(expected));
    }
    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
