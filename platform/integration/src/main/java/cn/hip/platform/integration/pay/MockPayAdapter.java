package cn.hip.platform.integration.pay;

import cn.hip.platform.integration.service.IntegrationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 聚合支付 Mock 适配器：本地生成二维码内容并留痕（无真实支付通道时使用） */
@Service
@RequiredArgsConstructor
public class MockPayAdapter implements PayAdapter {

    private final IntegrationLogService logService;

    @Override
    public PayResult createPayment(String payNo, BigDecimal amount, String channel) {
        String qr = "hippay://%s?payNo=%s&amount=%s".formatted(channel.toLowerCase(), payNo, amount);
        logService.log("OUT", "PAY", payNo,
                "{\"api\":\"pay.create\",\"payNo\":\"%s\",\"channel\":\"%s\",\"amount\":%s}"
                        .formatted(payNo, channel, amount), true, null);
        return new PayResult(true, qr, "mock ok");
    }

    @Override
    public PayResult refund(String payNo) {
        logService.log("OUT", "PAY", payNo,
                "{\"api\":\"pay.refund\",\"payNo\":\"%s\"}".formatted(payNo), true, null);
        return new PayResult(true, null, "mock ok");
    }
}
