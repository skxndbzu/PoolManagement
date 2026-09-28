package com.poolguard;
import com.poolguard.service.AnswerEvaluator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AnswerEvaluatorTest {
    private final AnswerEvaluator evaluator=new AnswerEvaluator();
    @Test void exactAnswerCannotPassOnPartialMatch(){
        assertThat(evaluator.passes(" ４０８ ","408")).isTrue();
        assertThat(evaluator.passes("4080","408")).isFalse();
        assertThat(evaluator.passes("","408")).isFalse();
        assertThat(evaluator.passes("{success:true}","408")).isFalse();
    }
    @Test void everyKeywordIsRequired(){
        assertThat(evaluator.passes("uses P95 and P99", "keywords:\nP95\nP99")).isTrue();
        assertThat(evaluator.passes("uses P95", "keywords:\nP95\nP99")).isFalse();
        assertThat(evaluator.passes("anything", "keywords:\n")).isFalse();
    }
}
