package cn.hip.insurance.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 医保每日自动对账：01:30 跑前一日，差异>0 自动开运维工单（sys_config yb_auto_recon_enabled 可停用） */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsuranceAutoReconJob {

    private final InsuranceReconService reconService;
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 30 1 * * *")
    public void dailyRecon() {
        var flag = jdbc.queryForList(
                "select cfg_value from sys_config where cfg_key = 'yb_auto_recon_enabled'", String.class);
        if (flag.isEmpty() || !"1".equals(flag.get(0))) {
            return;
        }
        String date = LocalDate.now().minusDays(1).toString();
        var result = reconService.reconcileAndSave(date);
        long diff = ((Number) result.get("diff")).longValue();
        log.info("医保自动对账 {}：共 {} 笔，差异 {} 笔", date, result.get("total"), diff);
        if (diff > 0) {
            jdbc.update("""
                    insert into ops_fault_ticket(title, level, status, reporter)
                    values (?, 'MEDIUM', 'OPEN', 'system')
                    """, "医保对账差异 %d 笔（%s），单号：%s".formatted(
                    diff, date, String.valueOf(result.get("diffDetail"))));
        }
    }
}
