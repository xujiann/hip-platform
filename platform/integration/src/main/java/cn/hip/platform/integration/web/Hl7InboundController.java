package cn.hip.platform.integration.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.integration.service.OruProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * HL7 V2 进站端点（HTTP 承载）。设备/中间件亦可走 MLLP/TCP（见 MllpServer），处理路径一致。
 */
@RestController
@RequestMapping("/api/integration/hl7")
@PreAuthorize("hasRole('ADMIN')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class Hl7InboundController {

    private final OruProcessingService oruProcessingService;

    /** 接收 ORU^R01 检验结果：OBR-2 为申请单号（我方 groupNo），OBX 为结果行 */
    @PostMapping(value = "/oru", consumes = "text/plain")
    public R<Map<String, Object>> receiveOru(@RequestBody String raw) {
        var result = oruProcessingService.process(raw);
        if (!result.ok()) {
            return R.fail(result.code(), result.message());
        }
        return R.ok(Map.of("groupNo", result.groupNo(), "items", result.items()));
    }
}
