package com.yonagi.verse.common.util;

/**
 * 敏感信息脱敏工具类
 *
 * @author Yonagi
 */
public class SensitiveUtil {

    private static final String MASK = "****";
    private static final String EMAIL_MASK = "***";
    private static final String API_MASK = "*";

    /**
     * 手机号脱敏：保留前3位和后4位，中间替换为 ****
     * 例：13812345678 → 138****5678
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + MASK + phone.substring(phone.length() - 4);
    }

    /**
     * 邮箱脱敏：保留首字母和域名，中间替换为 ***
     * 例：test@example.com → t***@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf('@');
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (localPart.length() <= 1) {
            return localPart + EMAIL_MASK + domain;
        }
        return localPart.charAt(0) + EMAIL_MASK + domain;
    }

    /**
     * 外部供应商的API Key脱敏，只保留前5位字符，后面替换为5个*号；如果长度小于5，则全部替换为*号
     * @param apiKey 外部API Key
     * @return 脱敏后的API Key
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        if (apiKey.length() <= 5) {
            return API_MASK.repeat(apiKey.length());
        }
        return apiKey.substring(0, 5) + API_MASK.repeat(5);
    }
}
