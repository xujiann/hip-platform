package cn.hip.platform.integration.repository;

import cn.hip.platform.integration.entity.IntMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntMessageLogRepository extends JpaRepository<IntMessageLog, Long> {

    List<IntMessageLog> findTop100ByOrderByIdDesc();

    List<IntMessageLog> findTop100ByChannelOrderByIdDesc(String channel);
}
