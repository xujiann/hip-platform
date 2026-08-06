# ADR-0002 医保适配器（InsuranceAdapter）实现约定

- 状态：已接受（2026-08-06，医保完善批次三）
- 背景：平台医保通道通过 `InsuranceAdapter` SPI 收敛省级差异；开发/演示/回归用 `MockInsuranceAdapter`，
  实施期替换为省医保 SDK 实现。留痕、对账、失败回滚语义均建立在实现方遵守以下约定之上。

## 接口契约

`cn.hip.platform.integration.insurance.InsuranceAdapter`：

| 方法 | 语义 | 返回 |
|---|---|---|
| `uploadSettlement(chargeNo, amount)` | 门诊/住院结算上传（住院传结算单号 CY*） | `InsuranceResult(ok, settleNo, message)` |
| `uploadRefund(chargeNo)` | 退费冲正（按本地单号；实现内部可用已回填的 `yb_settle_no` 换医保结算号） | 同上 |

## 实现方必须遵守

1. **结算号回传**：`uploadSettlement` 成功必须返回医保结算号 `settleNo`——平台回填
   `outp_charge.yb_settle_no` / `inp_settlement.yb_settle_no`（V40），冲正与线下对账依赖它。
2. **留痕约定（对账的通道账来源）**：每次出站调用须经 `IntegrationLogService.log(direction, channel, refNo, payload, ok, err)` 落
   `int_message_log`，且：
   - `channel = 'YB'`；`ref_no` = 本地单号（chargeNo / settleNo）；成功 `status = 'OK'`
   - `payload` 须包含 api 关键字：门诊结算 `outpatient.settle`、门诊冲正 `outpatient.refund`、住院结算含 `settle`
   - 对账（`InsuranceReconService`）按「ref_no 点查 + payload 关键字」判定报文存在；改关键字须同步改对账匹配
3. **失败语义**：不可用/被拒时返回 `ok=false`（勿抛异常吞掉 message）——平台按 5006/5007/9013 回滚本地事务。
   实现内部的重试须幂等（同单重复上传医保侧不可重复入账）。
4. **同步阻塞边界**：调用在收费事务内同步执行，实现须设置合理超时；长耗时操作（批量对账下载等）
   放实现自身的异步通道，不占用结算路径。

## 替换步骤

1. 新建实现类（建议放地区实现包，如 `cn.hip.impl.<region>.insurance`），实现上述契约；
2. 以 Spring Bean 替换 `MockInsuranceAdapter`（`@Primary` 或条件装配 `hip.insurance.adapter=sc-sdk`）；
3. 跑 `tools/e2e-insurance.py` 全 8 步（结算/分割/退费/对账断言均不依赖实现细节）；
4. 真实环境首日：人工触发一次 `/api/insurance/reconcile` 核对，再交给每日自动对账（01:30）。
