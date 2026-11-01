package com.yonagi.verse.adapter.skillhub;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/** 在真实建连的DNS解析点拒绝非公网地址，不执行校验后再次解析的TOCTOU流程。 */
final class SkillGithubDnsResolver implements DnsResolver {
    private static final Set<String> HOSTS = Set.of("api.github.com", "codeload.github.com");

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        requireHost(host);
        InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
        if (addresses.length == 0) {
            throw new UnknownHostException("GitHub地址不可解析");
        }
        for (InetAddress address : addresses) {
            if (!publicAddress(address)) {
                throw new UnknownHostException("GitHub解析到不允许的地址");
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        requireHost(host);
        return host;
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254) && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || second == 0 || (second == 88 && third == 99)))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    && !(first == 203 && second == 0 && third == 113);
        }
        // 仅接受全球单播，拒绝映射IPv4、NAT64、ULA、6to4和Teredo/文档地址。
        return bytes.length == 16 && (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01
                && ((bytes[2] == 0 && bytes[3] == 0)
                || (Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)));
    }

    private static void requireHost(String host) throws UnknownHostException {
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new UnknownHostException("不支持的GitHub目标主机");
        }
    }
}
