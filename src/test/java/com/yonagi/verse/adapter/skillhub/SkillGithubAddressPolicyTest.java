package com.yonagi.verse.adapter.skillhub;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** 仅验证地址字节的边界算法，不解析DNS、不建连，不据此证明真实网络路由。 */
class SkillGithubAddressPolicyTest {
    @ParameterizedTest
    @MethodSource("addresses")
    void onlySupportedPublicAddressFamiliesPass(String hexadecimal, boolean expected) throws Exception {
        assertEquals(expected, SkillGithubDnsResolver.publicAddress(InetAddress.getByAddress(HexFormat.of().parseHex(hexadecimal))));
    }

    static Stream<Arguments> addresses() {
        return Stream.of(
                Arguments.of("08080808", true), Arguments.of("01010101", true),
                Arguments.of("c0010203", true), Arguments.of("00000000", false),
                Arguments.of("7f000001", false), Arguments.of("0a000001", false),
                Arguments.of("ac100001", false), Arguments.of("ac1f0001", false),
                Arguments.of("c0a80001", false), Arguments.of("a9fe0101", false),
                Arguments.of("64400001", false), Arguments.of("647f0001", false),
                Arguments.of("c0000201", false), Arguments.of("c0586301", false),
                Arguments.of("c6120001", false), Arguments.of("c6130001", false),
                Arguments.of("c6336401", false), Arguments.of("cb007101", false),
                Arguments.of("e0000001", false), Arguments.of("ffffffff", false),
                Arguments.of("20014860486000000000000000008888", true),
                Arguments.of("260650c0000000000000000000000001", true),
                Arguments.of("00000000000000000000000000000000", false),
                Arguments.of("00000000000000000000000000000001", false),
                Arguments.of("fc000000000000000000000000000001", false),
                Arguments.of("fe800000000000000000000000000001", false),
                Arguments.of("0064ff9b00000000000000007f000001", false),
                Arguments.of("20020000000000000000000000000001", false),
                Arguments.of("20010000000000000000000000000001", false),
                Arguments.of("20010db8000000000000000000000001", false));
    }

    @Test
    void unsupportedHostsFailBeforeAnyDnsLookup() {
        var resolver = new SkillGithubDnsResolver();
        for (String host : new String[]{null, "localhost", "github.com", "api.github.com.attacker.test", "127.0.0.1"}) {
            assertThrows(UnknownHostException.class, () -> resolver.resolve(host));
            assertThrows(UnknownHostException.class, () -> resolver.resolveCanonicalHostname(host));
        }
        assertDoesNotThrow(() -> assertEquals("API.GITHUB.COM", resolver.resolveCanonicalHostname("API.GITHUB.COM")));
    }
}
