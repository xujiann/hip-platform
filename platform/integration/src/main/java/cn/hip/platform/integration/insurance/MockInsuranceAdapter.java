package cn.hip.platform.integration.insurance;

import cn.hip.platform.integration.service.IntegrationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/** 医保 Mock 适配器：本地直接返回成功并留痕（无真实医保环境时使用） */
@Service
@RequiredArgsConstructor
public class MockInsuranceAdapter implements InsuranceAdapter {

    private final IntegrationLogService logService;
    private final AtomicLong seq = new AtomicLong(1);

    @Override
    public InsuranceResult uploadSettlement(String chargeNo, BigDecimal amount) {
        String settleNo = "YB" + System.currentTimeMillis() / 1000 + "-" + seq.getAndIncrement();
        logService.log("OUT", "YB", chargeNo,
                "{\"api\":\"outpatient.settle\",\"chargeNo\":\"%s\",\"amount\":%s,\"ybSettleNo\":\"%s\"}"
                        .formatted(chargeNo, amount, settleNo), true, null);
        return new InsuranceResult(true, settleNo, "mock ok");
    }

    @Override
    public InsuranceResult uploadRefund(String chargeNo) {
        logService.log("OUT", "YB", chargeNo,
                "{\"api\":\"outpatient.refund\",\"chargeNo\":\"%s\"}".formatted(chargeNo), true, null);
        return new InsuranceResult(true, null, "mock ok");
    }
}
