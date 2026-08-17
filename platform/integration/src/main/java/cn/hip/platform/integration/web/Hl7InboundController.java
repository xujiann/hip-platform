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
// 1.1.4 B-16：机器对机器端点给专用 INTERFACE 角色——1.0.9 曾统一补成 ADMIN，
// 意味着 LIS 中间件的凭据是全院最高权限，泄漏即交出整个系统。INTERFACE 无任何菜单授权。
@RestController
@RequestMapping("/api/integration/hl7")
@PreAuthorize("hasAnyRole('ADMIN','INTERFACE')")
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
