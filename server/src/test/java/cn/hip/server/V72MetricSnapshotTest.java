package cn.hip.server;

import cn.hip.datagov.web.DataGovController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v72 包 D：指标快照按周期自动生成。
 *
 * <p><b>本类第一职责是锁定既有契约</b>：该端点此前<b>零 JUnit</b>，只有 {@code e2e-phase912}
 * 与 {@code e2e-phase2226} 两条 E2E 走过，它们断言的是返回体的 {@code snapshotted} 键
 * （分别要求 {@code >= 5} 与 {@code >= 9}）。本版要把内联在控制器里的快照逻辑抽进服务层，
 * 抽之前先把对外契约钉死——这是改既有路径唯一的安全网。
 *
 * <p>抽服务是因为**定时任务不该去调控制器**（本仓其余四个定时任务一律调服务层）。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V72MetricSnapshotTest {

    @Autowired DataGovController dataGovController;
    @Autowired cn.hip.datagov.service.MetricSnapshotScheduler scheduler;
    @Autowired JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot() {
        var r = dataGovController.snapshot();
        assertEquals(0, r.getCode(), r.getMessage());
        return (Map<String, Object>) r.getData();
    }

    /** 契约：返回体带 snapshotted，且计数与内置指标数一致（两套 E2E 正在断言这个键）。 */
    @Test
    void snapshotEndpointKeepsItsContract() {
        var body = snapshot();
        assertTrue(body.containsKey("snapshotted"),
                "两套 E2E 都在读 snapshotted，这个键不得改名：" + body.keySet());
        int n = ((Number) body.get("snapshotted")).intValue();
        assertTrue(n >= 5, "e2e-phase912 断言 >= 5，实得 " + n);
        assertTrue(n >= 9, "e2e-phase2226 断言 >= 9，实得 " + n);
    }

    /**
     * 幂等：同一天连跑两次不产生第二行，值被覆盖而不是追加。
     *
     * <p>这是能安全挂定时任务的前提——不幂等就不能让它每天自己跑。
     */
    @Test
    void snapshotIsIdempotentWithinTheSameDay() {
        snapshot();
        Integer first = jdbc.queryForObject(
                "select count(*) from dg_metric_snapshot where snap_date = current_date", Integer.class);
        snapshot();
        Integer second = jdbc.queryForObject(
                "select count(*) from dg_metric_snapshot where snap_date = current_date", Integer.class);
        assertEquals(first, second, "同日重复生成不得追加行（on conflict 覆盖）");
        assertNotNull(first);
        assertTrue(first > 0, "当日应有快照行");
    }

    /** 每个内置指标都落了一行，且 code 与定义表对得上。 */
    @Test
    void everyBuiltinMetricGetsARowForToday() {
        snapshot();
        List<String> defs = jdbc.queryForList(
                "select code from dg_metric_def where builtin_key is not null", String.class);
        List<String> snapped = jdbc.queryForList(
                "select code from dg_metric_snapshot where snap_date = current_date", String.class);
        assertFalse(defs.isEmpty(), "指标定义表不应为空");
        assertTrue(snapped.containsAll(defs),
                "每个内置指标当日都应有快照行；缺的是：" + defs.stream().filter(c -> !snapped.contains(c)).toList());
    }

    // ==================== ② 自动生成（664★） ====================

    /**
     * 开关打开时自动任务真的落了快照；<b>关闭时一行都不落</b>。
     *
     * <p>两侧都验——只验「开」的那一半，等于没验开关（开关坏成恒真也看不出来）。
     *
     * <p>注：调度器读开关走的是裸 JdbcTemplate 而非 ConfigReader，故不涉及方法论⑤的缓存下毒，
     * 不需要 evict；这与 CdrSyncScheduler 的既有写法一致。
     */
    @Test
    void autoSnapshotHonoursItsEnabledSwitchBothWays() {
        jdbc.update("delete from dg_metric_snapshot where snap_date = current_date");

        // ① 关闭：跑完一行不落
        setSwitch("0");
        scheduler.autoSnapshot();
        assertEquals(0, todayRows(), "开关关闭时不得生成任何快照行");

        // ② 打开：跑完落齐
        setSwitch("1");
        scheduler.autoSnapshot();
        assertTrue(todayRows() >= 9, "开关打开后应生成当日快照，实得 " + todayRows() + " 行");
    }

    /** 自动任务与手工端点写的是同一批数据——同日两者混跑不产生重复行。 */
    @Test
    void autoAndManualSnapshotShareTheSameIdempotentPath() {
        setSwitch("1");
        snapshot();
        int afterManual = todayRows();
        scheduler.autoSnapshot();
        assertEquals(afterManual, todayRows(), "自动任务不得在手工快照之外再追加行");
    }

    private void setSwitch(String v) {
        jdbc.update("insert into sys_config(cfg_key, cfg_value, remark) values (?, ?, ?) "
                + "on conflict (cfg_key) do update set cfg_value = excluded.cfg_value",
                cn.hip.datagov.service.MetricSnapshotScheduler.ENABLED_KEY, v, "v72 用例");
    }

    private int todayRows() {
        Integer n = jdbc.queryForObject(
                "select count(*) from dg_metric_snapshot where snap_date = current_date", Integer.class);
        return n == null ? 0 : n;
    }
}
