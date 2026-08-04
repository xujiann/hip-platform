package cn.hip.platform.integration.service;

import cn.hip.platform.integration.event.LabResultReceivedEvent;
import cn.hip.platform.integration.hl7.Hl7V2Message;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/** ORU^R01 处理：HTTP 端点与 MLLP 监听共用同一处理路径 */
@Service
@RequiredArgsConstructor
public class OruProcessingService {

    private final IntegrationLogService logService;
    private final ApplicationEventPublisher eventPublisher;

    public record OruResult(boolean ok, int code, String message, String groupNo, int items) {}

    public OruResult process(String raw) {
        String groupNo = null;
        try {
            Hl7V2Message msg = Hl7V2Message.parse(raw);
            if (!msg.messageType().startsWith("ORU")) {
                logService.log("IN", "HL7_LIS", null, raw, false, "unsupported type " + msg.messageType());
                return new OruResult(false, 7001, "仅支持 ORU^R01，收到: " + msg.messageType(), null, 0);
            }
            Hl7V2Message.Segment obr = msg.first("OBR");
            if (obr == null || obr.field(2).isBlank()) {
                logService.log("IN", "HL7_LIS", null, raw, false, "missing OBR-2");
                return new OruResult(false, 7002, "缺少申请单号（OBR-2）", null, 0);
            }
            groupNo = obr.field(2);
            List<LabResultReceivedEvent.Item> items = msg.all("OBX").stream()
                    .map(obx -> {
                        String[] id = obx.field(3).split("\\^", -1);
                        return new LabResultReceivedEvent.Item(
                                id.length > 0 ? id[0] : "",
                                id.length > 1 ? id[1] : id[0],
                                obx.field(5), obx.field(6), obx.field(7), obx.field(8));
                    })
                    .toList();
            if (items.isEmpty()) {
                logService.log("IN", "HL7_LIS", groupNo, raw, false, "no OBX");
                return new OruResult(false, 7003, "报文无结果行（OBX）", groupNo, 0);
            }
            eventPublisher.publishEvent(new LabResultReceivedEvent(groupNo, items));
            logService.log("IN", "HL7_LIS", groupNo, raw, true, null);
            return new OruResult(true, 0, "ok", groupNo, items.size());
        } catch (Exception e) {
            logService.log("IN", "HL7_LIS", groupNo, raw, false, e.toString());
            return new OruResult(false, 7000, "报文处理失败: " + e.getMessage(), groupNo, 0);
        }
    }
}
