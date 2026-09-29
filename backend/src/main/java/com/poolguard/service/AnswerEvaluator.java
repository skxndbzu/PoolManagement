package com.poolguard.service;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import com.poolguard.model.MatchMode;

@Component
public class AnswerEvaluator {
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:[.,][0-9]+)*|\\.[0-9]+)(?:e[+-]?[0-9]+)?");

    /** 默认包含匹配；数字按完整数值文本匹配，避免 21 命中 121、21.5 或 -21。 */
    public boolean passes(String answer, String expected) {
        return passes(answer, expected, MatchMode.FUZZY);
    }
    public boolean passes(String answer, String expected, MatchMode mode) {
        if (answer == null || answer.isBlank() || expected == null || expected.isBlank()) return false;
        if (mode == MatchMode.EXACT) return normalize(answer).equals(normalize(expected));
        // 兼容已保存的关键词题；新题通过匹配方式选择，无需填写前缀。
        if (expected.startsWith("keywords:")) {
            var words = expected.substring(9).lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
            return !words.isEmpty() && words.stream().allMatch(word -> contains(answer, word));
        }
        return contains(answer, expected);
    }
    private boolean contains(String answer, String expected) {
        String actual = normalize(answer), target = normalize(expected);
        if (!NUMBER.matcher(target).matches()) return actual.contains(target);
        var numbers = NUMBER.matcher(actual);
        while (numbers.find()) if (numbers.group().equals(target)) return true;
        return false;
    }
    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
