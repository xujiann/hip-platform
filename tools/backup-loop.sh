#!/bin/sh
# 生产备份循环（v29 P1-1）：随 docker-compose.full.yml 的 db-backup 服务运行。
# db-backup.ps1 是 Windows+WSL 开发态脚本，Linux 机房用不了——生产备份必须容器化交付。
#
# 每天到点跑 pg_dump -Fc（自定义格式，pg_restore 可用），落到与 pg 共享的 ./backups 卷，
# 保留最近 N 份。容器 TZ 已固定 Asia/Shanghai，故 date 取的是北京时。
#
# 异地外传（rsync / 对象存储）不在本脚本内——各院网络与合规要求不同，留实施配置，
# 见部署手册「备份与恢复」。本脚本只保证「同机每日有可恢复的冷备」这条底线。
set -eu

BACKUP_DIR=/backups
PGHOST=postgres
PGUSER=hip
PGDB=hip
KEEP=${HIP_BACKUP_KEEP:-14}          # 保留份数
HOUR=${HIP_BACKUP_HOUR:-02}          # 每天几点（两位，北京时）
MINUTE=${HIP_BACKUP_MINUTE:-30}

echo "[backup-loop] 启动：每天 ${HOUR}:${MINUTE} 备份 ${PGDB}@${PGHOST}，保留 ${KEEP} 份 → ${BACKUP_DIR}"

run_backup() {
    ts=$(date +%Y%m%d-%H%M%S)
    out="${BACKUP_DIR}/hip-${ts}.dump"
    tmp="${out}.partial"
    echo "[backup-loop] $(date '+%F %T') 开始备份 → ${out}"
    # 先写 .partial，成功且非空才改名——避免中断的半截文件被当成有效备份（db-backup.ps1 同纪律）
    if pg_dump -h "$PGHOST" -U "$PGUSER" -d "$PGDB" -Fc -f "$tmp" && [ -s "$tmp" ]; then
        mv "$tmp" "$out"
        echo "[backup-loop] $(date '+%F %T') 备份成功 $(du -h "$out" | cut -f1)"
        # 保留最近 KEEP 份，多余的按时间删除
        ls -1t "${BACKUP_DIR}"/hip-*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
            echo "[backup-loop] 清理旧备份 ${old}"
            rm -f "$old"
        done
    else
        rm -f "$tmp"
        echo "[backup-loop] $(date '+%F %T') !! 备份失败——保留告警，OpsHealthScheduler 会开「24 小时内无成功备份」工单" >&2
    fi
}

# 主循环：每分钟检查一次是否到点。到点当分钟只跑一次（记录已跑日期）
last_run_day=""
while true; do
    now_hm=$(date +%H:%M)
    today=$(date +%Y-%m-%d)
    if [ "$now_hm" = "${HOUR}:${MINUTE}" ] && [ "$last_run_day" != "$today" ]; then
        run_backup
        last_run_day="$today"
    fi
    sleep 30
done
