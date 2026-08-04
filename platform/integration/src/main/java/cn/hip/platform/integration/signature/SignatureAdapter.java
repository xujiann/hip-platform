package cn.hip.platform.integration.signature;

/**
 * CA 电子签名适配器 SPI。
 * 真实环境接入 CA 厂商 SDK（如 CFCA/BJCA），Mock 用本地摘要模拟签名值。
 */
public interface SignatureAdapter {

    record SignResult(boolean ok, String signature, String message) {}

    /** 对内容摘要签名，返回签名值（Base64） */
    SignResult sign(String content, Long signerId);

    /** 验签 */
    boolean verify(String content, String signature);
}
