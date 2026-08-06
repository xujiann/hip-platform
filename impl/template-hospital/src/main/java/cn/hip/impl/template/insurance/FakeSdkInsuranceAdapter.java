package cn.hip.impl.template.insurance;

import cn.hip.platform.integration.insurance.InsuranceAdapter;
import cn.hip.platform.integration.service.IntegrationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 彩排用「省 SDK」医保适配器示例：演示 L3 替换路径（@Primary 覆盖 Mock）。
 * 严格遵守 ADR-0002：结算号回传（YB 前缀）、int_message_log 留痕含 api 关键字、失败返回 ok=false。
 * 真实实施时以省医保 SDK 调用替换本类内部实现，契约不变。
 */
@Service
@Primary
@RequiredArgsConstructor
public class FakeSdkInsuranceAdapter implements InsuranceAdapter {

    private final IntegrationLogService logService;
    private final AtomicLong seq = new AtomicLong(1);

    @Override
    public InsuranceResult uploadSettlement(String chargeNo, BigDecimal amount) {
        // 真实实现：sdk.signIn() → sdk.uploadSettlement(...) → 解析返回
        String settleNo = "YBSDK" + System.currentTimeMillis() / 1000 + "-" + seq.getAndIncrement();
        logService.log("OUT", "YB", chargeNo,
                "{\"api\":\"outpatient.settle\",\"provider\":\"fake-sdk\",\"chargeNo\":\"%s\",\"amount\":%s,\"ybSettleNo\":\"%s\"}"
                        .formatted(chargeNo, amount, settleNo), true, null);
        return new InsuranceResult(true, settleNo, "fake-sdk ok");
    }

    @Override
    public InsuranceResult uploadRefund(String chargeNo) {
        logService.log("OUT", "YB", chargeNo,
                "{\"api\":\"outpatient.refund\",\"provider\":\"fake-sdk\",\"chargeNo\":\"%s\"}".formatted(chargeNo),
                true, null);
        return new InsuranceResult(true, null, "fake-sdk ok");
    }
}
