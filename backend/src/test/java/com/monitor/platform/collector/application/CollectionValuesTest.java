package com.monitor.platform.collector.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集数值换算契约。
 *
 * <p>这些换算被采集编排与快照写入共用，边界（空值、精度比较）必须稳定。</p>
 */
class CollectionValuesTest {

    @Test
    void convertsNullableNumbers() {
        assertThat(CollectionValues.decimal((Long) null)).isNull();
        assertThat(CollectionValues.decimal(5L)).isEqualByComparingTo("5");
        assertThat(CollectionValues.decimal((Double) null)).isNull();
        assertThat(CollectionValues.decimal(1.5d)).isEqualByComparingTo("1.5");
    }

    @Test
    void comparesNumericallyIgnoringScale() {
        assertThat(CollectionValues.different(new BigDecimal("1.0"), new BigDecimal("1.00"))).isFalse();
        assertThat(CollectionValues.different(new BigDecimal("1.0"), new BigDecimal("1.01"))).isTrue();
        assertThat(CollectionValues.different(null, BigDecimal.ONE)).isTrue();
        assertThat(CollectionValues.different(null, null)).isFalse();
    }

    @Test
    void convertsNewApiQuotaToUsd() {
        assertThat(CollectionValues.newApiUsd(500000L)).isEqualByComparingTo("1");
        assertThat(CollectionValues.newApiUsd(250000L)).isEqualByComparingTo("0.5");
        assertThat(CollectionValues.newApiUsd(null)).isNull();
    }

    @Test
    void truncatesLongText() {
        assertThat(CollectionValues.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(CollectionValues.truncate("ab", 3)).isEqualTo("ab");
        assertThat(CollectionValues.truncate(null, 3)).isNull();
    }
}
