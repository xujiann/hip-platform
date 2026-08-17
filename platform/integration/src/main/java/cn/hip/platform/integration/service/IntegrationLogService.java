package cn.hip.platform.integration.service;

import cn.hip.platform.integration.entity.IntMessageLog;
import cn.hip.platform.integration.repository.IntMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationLogService {

    private final IntMessageLogRepository repository;

    /**
     * 独立事务：业务回滚也保留通信痕迹。
     *
     * <p>**所有**字段都要按列宽截断，且留痕失败本身不能往外抛：
     * ref_no 取自设备可控的 OBR-2、error 取自异常 toString，超长会抛
     * DataIntegrityViolationException，从 REQUIRES_NEW 事务穿出、越过调用方的兜底 catch、
     * 一路逃到 MllpServer.handle()（只 catch IOException）→ 连接被关、不回 ACK、消息静默丢失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String direction, String channel, String refNo, String payload, boolean ok, String error) {
        try {
            IntMessageLog m = new IntMessageLog();
            m.setDirection(truncate(direction, 8));
            m.setChannel(truncate(channel, 32));
            m.setRefNo(truncate(refNo, 64));
            m.setPayload(payload);   // payload 已是 text（V55）：截断的报文留痕不可用于对账举证
            m.setStatus(ok ? "OK" : "FAIL");
            m.setError(truncate(error, 512));
            repository.save(m);
        } catch (Exception e) {
            // 留痕是旁路，绝不能影响主链路（尤其不能让 MLLP 因此不回 ACK）
            log.error("集成留痕写入失败 direction={} channel={} refNo={}: {}",
                    direction, channel, refNo, e.getMessage());
        }
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
