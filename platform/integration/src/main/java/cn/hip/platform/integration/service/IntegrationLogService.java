package cn.hip.platform.integration.service;

import cn.hip.platform.integration.entity.IntMessageLog;
import cn.hip.platform.integration.repository.IntMessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationLogService {

    private final IntMessageLogRepository repository;

    /** 独立事务：业务回滚也保留通信痕迹 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String direction, String channel, String refNo, String payload, boolean ok, String error) {
        IntMessageLog m = new IntMessageLog();
        m.setDirection(direction);
        m.setChannel(channel);
        m.setRefNo(refNo);
        m.setPayload(payload != null && payload.length() > 7900 ? payload.substring(0, 7900) : payload);
        m.setStatus(ok ? "OK" : "FAIL");
        m.setError(error);
        repository.save(m);
    }
}
