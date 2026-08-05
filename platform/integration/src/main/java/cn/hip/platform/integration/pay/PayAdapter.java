package cn.hip.platform.integration.pay;

import java.math.BigDecimal;

/**
 * 聚合支付适配器 SPI（微信/支付宝扫码）。
 * 真实环境替换为聚合支付平台实现（下单/回调验签/退款），Mock 用于开发与演示。
 */
public interface PayAdapter {

    record PayResult(boolean ok, String qrContent, String message) {}

    /** 创建扫码支付单，返回二维码内容 */
    PayResult createPayment(String payNo, BigDecimal amount, String channel);

    /** 退款冲正 */
    PayResult refund(String payNo);
}
