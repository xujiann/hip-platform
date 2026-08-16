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
            // 一条 ORU 可携带多个 OBR 组（LIS 按患者批量回传是常见做法）。
            // 只取 first("OBR") 再把 all("OBX") 全打包，会把 B 申请单的结果写进 A 单——
            // 静默的检验结果串号，临床不可察。必须按报文原序切组。
            var groups = new java.util.LinkedHashMap<String, List<LabResultReceivedEvent.Item>>();
            String current = null;
            for (var seg : msg.segmentsInOrder()) {
                if ("OBR".equals(seg.name())) {
                    current = seg.field(2).isBlank() ? null : seg.field(2);
                    if (current != null) {
                        groups.computeIfAbsent(current, k -> new java.util.ArrayList<>());
                    }
                } else if ("OBX".equals(seg.name()) && current != null) {
                    String[] id = seg.field(3).split("\\^", -1);
                    groups.get(current).add(new LabResultReceivedEvent.Item(
                            id.length > 0 ? id[0] : "",
                            id.length > 1 ? id[1] : id[0],
                            seg.field(5), seg.field(6), seg.field(7), seg.field(8)));
                }
            }
            if (groups.isEmpty()) {
                logService.log("IN", "HL7_LIS", null, raw, false, "missing OBR-2");
                return new OruResult(false, 7002, "缺少申请单号（OBR-2）", null, 0);
            }
            groupNo = groups.keySet().iterator().next();
            int total = 0;
            for (var entry : groups.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;   // 该 OBR 组无结果行，跳过而非把别组的结果算到它头上
                }
                eventPublisher.publishEvent(new LabResultReceivedEvent(entry.getKey(), entry.getValue()));
                logService.log("IN", "HL7_LIS", entry.getKey(), raw, true, null);
                total += entry.getValue().size();
            }
            if (total == 0) {
                logService.log("IN", "HL7_LIS", groupNo, raw, false, "no OBX");
                return new OruResult(false, 7003, "报文无结果行（OBX）", groupNo, 0);
            }
            return new OruResult(true, 0, "ok", groupNo, total);
        } catch (Exception e) {
            logService.log("IN", "HL7_LIS", groupNo, raw, false, e.toString());
            return new OruResult(false, 7000, "报文处理失败: " + e.getMessage(), groupNo, 0);
        }
    }
}
