package cn.hip.platform.integration.insurance;

import java.math.BigDecimal;

/**
 * 医保接口适配器 SPI。
 * 真实环境替换为国家医保局接口实现（签到/结算/退费/对账），Mock 用于开发与演示。
 */
public interface InsuranceAdapter {

    record InsuranceResult(boolean ok, String settleNo, String message) {}

    /** 门诊结算上传 */
    InsuranceResult uploadSettlement(String chargeNo, BigDecimal amount);

    /** 退费冲正 */
    InsuranceResult uploadRefund(String chargeNo);
}
