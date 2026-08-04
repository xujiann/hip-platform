package cn.hip.platform.integration.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.integration.entity.IntMessageLog;
import cn.hip.platform.integration.repository.IntMessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/integration/logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class IntegrationMonitorController {

    private final IntMessageLogRepository repository;

    @GetMapping
    public R<List<IntMessageLog>> list(@RequestParam(required = false) String channel) {
        return R.ok(channel == null
                ? repository.findTop100ByOrderByIdDesc()
                : repository.findTop100ByChannelOrderByIdDesc(channel));
    }
}
