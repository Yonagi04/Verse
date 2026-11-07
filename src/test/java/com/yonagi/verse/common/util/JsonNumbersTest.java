package com.yonagi.verse.common.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonNumbersTest {
    @ParameterizedTest
    @MethodSource("positiveIntegers")
    void acceptsExactPositiveIntegersWithinInclusiveLimit(Object value, long maximum) {
        assertTrue(JsonNumbers.isPositiveInteger(value, maximum));
    }

    private static Stream<Arguments> positiveIntegers() {
        return Stream.of(
                Arguments.of((byte) 1, 1L), Arguments.of((short) 1, 1L),
                Arguments.of(1, 1L), Arguments.of(1L, 1L),
                Arguments.of(Integer.MAX_VALUE, (long) Integer.MAX_VALUE),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(new BigInteger("9223372036854775807"), Long.MAX_VALUE),
                Arguments.of(new BigDecimal("9223372036854775807.000"), Long.MAX_VALUE),
                Arguments.of(new BigDecimal("1.000"), 1L),
                Arguments.of(new BigDecimal("1e3"), 1000L),
                Arguments.of(1.0D, 1L), Arguments.of(1.0F, 1L));
    }

    @ParameterizedTest
    @MethodSource("invalidIntegers")
    void rejectsWrongTypesFractionsOverflowAndNonFiniteNumbers(Object value, long maximum) {
        assertFalse(JsonNumbers.isPositiveInteger(value, maximum));
    }

    private static Stream<Arguments> invalidIntegers() {
        return Stream.of(
                Arguments.of(null, 10L), Arguments.of("1", 10L), Arguments.of(true, 10L),
                Arguments.of(new AtomicLong(1L), 10L),
                Arguments.of(0, 10L), Arguments.of(-1, 10L), Arguments.of(11L, 10L),
                Arguments.of(1, 0L), Arguments.of(1, -1L),
                Arguments.of(new BigInteger("9223372036854775808"), Long.MAX_VALUE),
                Arguments.of(new BigDecimal("1.00000000000000000001"), 10L),
                Arguments.of(new BigDecimal("2147483647.00000000000001"), (long) Integer.MAX_VALUE),
                Arguments.of(new BigDecimal("9223372036854775807.0000000001"), Long.MAX_VALUE),
                Arguments.of(new BigDecimal("0.0"), 10L),
                Arguments.of(new BigDecimal("-1.0"), 10L),
                Arguments.of(new BigDecimal("1e-1000"), 10L),
                Arguments.of(new BigDecimal("1e1000"), Long.MAX_VALUE),
                Arguments.of(1.5D, 10L), Arguments.of(1.5F, 10L),
                Arguments.of((double) Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(Double.NaN, 10L), Arguments.of(Double.POSITIVE_INFINITY, 10L),
                Arguments.of(Double.NEGATIVE_INFINITY, 10L),
                Arguments.of(Float.NaN, 10L), Arguments.of(Float.POSITIVE_INFINITY, 10L));
    }
}
