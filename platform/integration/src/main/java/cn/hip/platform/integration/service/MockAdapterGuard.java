package cn.hip.platform.integration.service;

import cn.hip.platform.integration.insurance.InsuranceAdapter;
import cn.hip.platform.integration.insurance.MockInsuranceAdapter;
import cn.hip.platform.integration.pay.MockPayAdapter;
import cn.hip.platform.integration.pay.PayAdapter;
import cn.hip.platform.integration.signature.MockSignatureAdapter;
import cn.hip.platform.integration.signature.SignatureAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 试点/生产启动自检：任一 SPI 仍是 Mock 实现即阻断启动。
 *
 * <p>Mock 医保适配器对每次结算无条件返回成功并自写 int_message_log，而对账正是以该留痕为准——
 * 漏切真实实现时，结算是假的、对账 100% 匹配、零告警，问题要到医保拒付才暴露。
 * Mock 签名适配器只做 SHA-256 摘要，不具电子签名法律效力。故此处以启动失败换取零静默风险。
 *
 * <p>实施时按 ADR-0002 提供 @Primary 的真实实现即可通过（虚拟医院彩排已验证该替换路径）。
 */
@Component
@RequiredArgsConstructor
public class MockAdapterGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final List<String> GUARDED_PROFILES = List.of("pilot", "prod");

    private final InsuranceAdapter insuranceAdapter;
    private final PayAdapter payAdapter;
    private final SignatureAdapter signatureAdapter;
    private final Environment environment;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (!guarded) {
            return;
        }
        var mocks = new ArrayList<String>();
        if (insuranceAdapter instanceof MockInsuranceAdapter) mocks.add("InsuranceAdapter（医保结算与对账）");
        if (payAdapter instanceof MockPayAdapter) mocks.add("PayAdapter（聚合支付）");
        if (signatureAdapter instanceof MockSignatureAdapter) mocks.add("SignatureAdapter（病历 CA 签名）");
        if (mocks.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "拒绝在 pilot/prod 运行 Mock 适配器：" + String.join("、", mocks)
                        + "。Mock 会无条件返回成功且自写留痕（对账将恒为匹配），必须按 ADR-0002 "
                        + "提供 @Primary 真实实现；若该模块本次未采购，请用模块开关关闭而非留 Mock。");
    }
}
