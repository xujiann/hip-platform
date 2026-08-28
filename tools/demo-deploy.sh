#!/usr/bin/env bash
# HIP 在线演示环境一键部署（面向一台干净的 Linux 服务器）
#
# 与 deploy/docker-compose.full.yml 的关系：compose 负责「把服务跑起来」，
# 本脚本负责「跑起来之后还能演示」——灌演示数据、建角色账号、验证各模块非空，
# 并强制处理默认口令（演示环境在公网，admin/admin123 不能留）。
#
# 用法：
#   export HIP_DEMO_ADMIN_PASSWORD='<你的强口令>'     # 必填，用于替换默认口令
#   bash tools/demo-deploy.sh                          # 部署 + 灌数据
#   bash tools/demo-deploy.sh --data-only              # 仅重灌演示数据（服务已在跑）
#   bash tools/demo-deploy.sh --down                   # 停止并清理容器（数据卷保留）
#
# 前置：Docker 与 Docker Compose 插件、开放 80 端口、仓库已 clone 到本机。

set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="deploy/docker-compose.full.yml"
BASE="${HIP_DEMO_BASE:-http://localhost/api}"

# 能否用该口令登录。改密之后默认口令即失效，而灌数据、E2E 都要登录——
# 不探测就会出现「第二次跑脚本全盘失败」
login_ok() {
    curl -sf -X POST "$BASE/auth/login" -H 'Content-Type: application/json'         -d "{\"username\":\"$1\",\"password\":\"$2\"}" 2>/dev/null | grep -q '"token"'
}

if [[ "${1:-}" == "--down" ]]; then
    docker compose -f "$COMPOSE" down
    echo "已停止（数据卷保留，再次 up 即恢复现场；要连数据一起清除加 -v）"
    exit 0
fi

if [[ "${1:-}" != "--data-only" ]]; then
    # ── 1. 凭据 ────────────────────────────────────────────────
    # compose 对这三个变量是 :?required，缺失即拒绝启动（不提供默认值兜底）
    : "${HIP_DB_PASSWORD:=$(openssl rand -base64 24)}"
    : "${HIP_JWT_SECRET:=$(openssl rand -base64 48)}"
    export HIP_DB_PASSWORD HIP_JWT_SECRET

    if [[ -z "${HIP_DEMO_ADMIN_PASSWORD:-}" ]]; then
        echo "错误：必须设置 HIP_DEMO_ADMIN_PASSWORD" >&2
        echo "      演示环境在公网可达，默认口令 admin/admin123 不能保留。" >&2
        exit 1
    fi

    # 生成的随机口令要落盘，否则重启 compose 时对不上已初始化的数据库
    if [[ ! -f .demo-env ]]; then
        umask 077
        cat > .demo-env <<EOF
export HIP_DB_PASSWORD='$HIP_DB_PASSWORD'
export HIP_JWT_SECRET='$HIP_JWT_SECRET'
EOF
        echo "[1/4] 已生成凭据并写入 .demo-env（权限 600，勿入版本库）"
    else
        # shellcheck disable=SC1091
        source .demo-env
        echo "[1/4] 复用既有凭据 .demo-env"
    fi

    # ── 2. 起服务 ──────────────────────────────────────────────
    echo "[2/4] 构建并启动容器（首次约 3-5 分钟）..."
    docker compose -f "$COMPOSE" up -d --build

    # 用登录端点判就绪，而不是 /actuator/health——nginx 只代理 /api/，
    # actuator 在容器外根本不可达；登录成功还顺带验证了 nginx→后端→数据库全链路
    echo -n "      等待全链路就绪"
    # 循环内只试默认口令：若 HIP_DEMO_ADMIN_PASSWORD 拼错，每 4 秒双试会在 3 轮内
    # 触发 5 次失败锁定 15 分钟，最终误报"未就绪"（第六轮审阅 P3）。
    # 服务未就绪时连接被拒不计失败次数，试默认口令是安全的
    ready=0
    for _ in $(seq 1 90); do
        if login_ok admin admin123; then
            echo " —— 就绪"; ready=1; break
        fi
        echo -n "."; sleep 4
    done
    # 已改过密的二次部署：默认口令必然失败，此时才用目标口令**单次**判定
    if [[ $ready -ne 1 ]] && login_ok admin "${HIP_DEMO_ADMIN_PASSWORD:-}"; then
        echo " —— 就绪（admin 已非默认口令）"; ready=1
    fi
    if [[ $ready -ne 1 ]]; then
        echo
        echo "错误：全链路未就绪，请查看 docker compose -f $COMPOSE logs server" >&2
        exit 1
    fi
fi

# ── 3. 演示数据 ────────────────────────────────────────────────
echo "[3/4] 灌演示数据..."
# 判定当前生效的是默认口令还是已改过的口令，据此驱动 bootstrap 与 E2E
if login_ok admin admin123; then
    ADMIN_PWD='admin123'
elif login_ok admin "${HIP_DEMO_ADMIN_PASSWORD:-}"; then
    ADMIN_PWD="$HIP_DEMO_ADMIN_PASSWORD"
    echo "      admin 口令此前已改，沿用 HIP_DEMO_ADMIN_PASSWORD"
else
    echo "错误：admin 既非默认口令也非 HIP_DEMO_ADMIN_PASSWORD，无法灌数据" >&2
    exit 1
fi
export HIP_BASE="$BASE" HIP_E2E_BASE="$BASE"
export HIP_PASSWORD="$ADMIN_PWD" HIP_E2E_PASSWORD="$ADMIN_PWD"
python3 tools/bootstrap-demo.py 2>&1 | tail -2

# 顺序与 CI 一致：patient 360 要求同一患者兼有门诊/检验/住院文档，
# 检验那部分由 integration 的 HL7 ORU 产生，少跑一套它就断言失败
FLOWS=(
    e2e-outpatient e2e-inpatient e2e-integration e2e-mllp e2e-phase3
    e2e-phase48 e2e-phase912 e2e-phase1316 e2e-phase1821 e2e-phase2226
    e2e-insurance e2e-phase2931 e2e-phase3234 e2e-phase3537 e2e-phase38
    e2e-phase3940 e2e-product1 e2e-101 e2e-drg-cdss e2e-emr-stats
)
failed=0
for f in "${FLOWS[@]}"; do
    if ! python3 "tools/$f.py" > /dev/null 2>&1; then
        echo "      !! $f 未通过——对应模块演示页可能无数据"
        failed=$((failed + 1))
    fi
done
if [[ $failed -eq 0 ]]; then
    echo "      ${#FLOWS[@]} 条业务链路全部通过"
else
    echo "      $failed 条未通过（见上）"
fi

# ── 4. 口令收口 ────────────────────────────────────────────────
# 演示环境公网可达，默认口令必须改掉；角色账号的 Demo1234 仅为只读演示保留
if [[ -n "${HIP_DEMO_ADMIN_PASSWORD:-}" && "$ADMIN_PWD" == 'admin123' ]]; then
    echo "[4/4] 修改 admin 默认口令..."
    # 必须走**自助改密端点**（v27-A 新增），不能走管理员的 PUT /system/users/{id}——
    # 后者按设计给目标账号置 must_change_password（"管理员代改=重发初始口令"语义），
    # 会让 admin 首登被强制再改一次，HIP_DEMO_ADMIN_PASSWORD 即刻作废、
    # --data-only 重跑也会被 1009 兜底拦死（第六轮审阅 P1-B）
    python3 - <<'PY'
import json, os, urllib.request
base = os.environ['HIP_BASE']


def call(m, p, b=None, t=None):
    r = urllib.request.Request(base + p, method=m)
    r.add_header('Content-Type', 'application/json')
    if t:
        r.add_header('Authorization', 'Bearer ' + t)
    with urllib.request.urlopen(r, data=json.dumps(b).encode() if b is not None else None) as x:
        return json.loads(x.read().decode())


t = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})['data']['token']
r = call('POST', '/auth/change-password',
         {'oldPassword': 'admin123', 'newPassword': os.environ['HIP_DEMO_ADMIN_PASSWORD']}, t)
if r.get('code') == 0:
    print('      admin 口令已更新（自助路径，不触发首登强制改密）')
else:
    print(f"      !! 口令更新失败：{r.get('message')}")
    raise SystemExit(1)
PY
else
    echo "[4/4] admin 口令已非默认值，跳过"
fi

cat <<EOF

  在线演示环境就绪
  地址      http://<服务器地址>/          （建议在其前面挂 HTTPS 反代）
  管理员    admin / <HIP_DEMO_ADMIN_PASSWORD>
  角色账号  doctor01 / nurse01 / cashier01 / pharm01 / tech01 / quality01 / ops01
            统一密码 Demo1234
  重灌数据  bash tools/demo-deploy.sh --data-only
  停止      bash tools/demo-deploy.sh --down

  注意：演示数据均为虚构，切勿录入真实患者信息。
EOF
