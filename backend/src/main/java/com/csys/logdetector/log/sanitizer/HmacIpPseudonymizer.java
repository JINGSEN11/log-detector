package com.csys.logdetector.log.sanitizer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
public class HmacIpPseudonymizer implements IpPseudonymizer {

    private final byte[] key;

    public HmacIpPseudonymizer(@Value("${app.log.ip-hmac-key:local-development-only-change-this-key-32bytes}") String configuredKey) {
        if (configuredKey == null || configuredKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("LOG_IP_HMAC_KEY 必须配置且至少为 32 字节");
        }
        this.key = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String pseudonymize(String ip) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法生成来源伪名", ex);
        }
    }
}
