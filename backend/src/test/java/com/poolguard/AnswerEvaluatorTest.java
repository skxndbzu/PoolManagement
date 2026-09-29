package com.poolguard;
import com.poolguard.service.AnswerEvaluator;
import com.poolguard.model.MatchMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AnswerEvaluatorTest {
    private final AnswerEvaluator evaluator=new AnswerEvaluator();
    @Test void exactAnswerCannotPassOnPartialMatch(){
        assertThat(evaluator.passes(" ４０８ ","408", MatchMode.EXACT)).isTrue();
        assertThat(evaluator.passes("4080","408", MatchMode.EXACT)).isFalse();
        assertThat(evaluator.passes("答案为 408。", "408", MatchMode.EXACT)).isFalse();
        assertThat(evaluator.passes("","408")).isFalse();
        assertThat(evaluator.passes("{success:true}","408")).isFalse();
    }
    @Test void fuzzyAcceptsExplainedAnswerButNotPartsOfOtherNumbers(){
        assertThat(evaluator.passes("最少需要取出 **21 颗**。\n取9颗圆形糖、12颗五角星糖，因此答案是21。", "21")).isTrue();
        assertThat(evaluator.passes("最终答案：\\boxed{２１}", "21")).isTrue();
        for (String wrong : new String[]{"121", "210", "21.5", "-21", "+21", "0.21", "21e3", "1,021"})
            assertThat(evaluator.passes("答案是 " + wrong + "。", "21")).as(wrong).isFalse();
        assertThat(evaluator.passes("经过分析，答案是 Hello   WORLD。", "hello world")).isTrue();
        assertThat(evaluator.passes(null, "21")).isFalse();
        assertThat(evaluator.passes("21", " ")).isFalse();
    }
    @Test void everyKeywordIsRequired(){
        assertThat(evaluator.passes("uses P95 and P99", "keywords:\nP95\nP99")).isTrue();
        assertThat(evaluator.passes("uses P95", "keywords:\nP95\nP99")).isFalse();
        assertThat(evaluator.passes("anything", "keywords:\n")).isFalse();
    }
}
