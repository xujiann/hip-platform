package cn.hip.platform.integration.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.integration.event.LabResultReceivedEvent;
import cn.hip.platform.integration.hl7.Hl7V2Message;
import cn.hip.platform.integration.service.IntegrationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * HL7 V2 进站端点（LIS 设备/中间件推送）。
 * 生产环境通常走 MLLP/TCP，本端点为 HTTP 承载的同构实现，报文格式一致。
 */
@RestController
@RequestMapping("/api/integration/hl7")
@RequiredArgsConstructor
public class Hl7InboundController {

    private final IntegrationLogService logService;
    private final ApplicationEventPublisher eventPublisher;

    /** 接收 ORU^R01 检验结果：OBR-2 为申请单号（我方 groupNo），OBX 为结果行 */
    @PostMapping(value = "/oru", consumes = "text/plain")
    public R<Map<String, Object>> receiveOru(@RequestBody String raw) {
        String groupNo = null;
        try {
            Hl7V2Message msg = Hl7V2Message.parse(raw);
            if (!msg.messageType().startsWith("ORU")) {
                logService.log("IN", "HL7_LIS", null, raw, false, "unsupported type " + msg.messageType());
                return R.fail(7001, "仅支持 ORU^R01，收到: " + msg.messageType());
            }
            Hl7V2Message.Segment obr = msg.first("OBR");
            if (obr == null || obr.field(2).isBlank()) {
                logService.log("IN", "HL7_LIS", null, raw, false, "missing OBR-2");
                return R.fail(7002, "缺少申请单号（OBR-2）");
            }
            groupNo = obr.field(2);
            List<LabResultReceivedEvent.Item> items = msg.all("OBX").stream()
                    .map(obx -> {
                        // OBX-3 观察项 code^name，OBX-5 值，OBX-6 单位，OBX-7 参考范围，OBX-8 异常标志
                        String[] id = obx.field(3).split("\\^", -1);
                        return new LabResultReceivedEvent.Item(
                                id.length > 0 ? id[0] : "",
                                id.length > 1 ? id[1] : id[0],
                                obx.field(5), obx.field(6), obx.field(7), obx.field(8));
                    })
                    .toList();
            if (items.isEmpty()) {
                logService.log("IN", "HL7_LIS", groupNo, raw, false, "no OBX");
                return R.fail(7003, "报文无结果行（OBX）");
            }
            eventPublisher.publishEvent(new LabResultReceivedEvent(groupNo, items));
            logService.log("IN", "HL7_LIS", groupNo, raw, true, null);
            return R.ok(Map.of("groupNo", groupNo, "items", items.size()));
        } catch (Exception e) {
            logService.log("IN", "HL7_LIS", groupNo, raw, false, e.toString());
            return R.fail(7000, "报文处理失败: " + e.getMessage());
        }
    }
}
