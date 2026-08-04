package cn.hip.platform.integration.signature;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 签名 Mock：SHA-256 摘要模拟签名值（内容防篡改可验，无法证明签名人身份——生产须换 CA SDK） */
@Service
public class MockSignatureAdapter implements SignatureAdapter {

    @Override
    public SignResult sign(String content, Long signerId) {
        return new SignResult(true, digest(content, signerId), "mock ok");
    }

    @Override
    public boolean verify(String content, String signature) {
        return signature != null && signature.startsWith(digest(content, null).substring(0, 20));
    }

    private String digest(String content, Long signerId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            String base = Base64.getEncoder().encodeToString(hash);
            return signerId == null ? base : base.substring(0, 20) + ":" + signerId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
