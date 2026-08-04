#!/usr/bin/env bash
# 端到端冒烟测试：登录 → 取当前用户 → 系统管理接口
# 前提：后端已在 8080 端口运行
set -e
BASE=http://localhost:8080/api

echo "[1] 登录 admin ..."
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d;print(d['data']['token'])")
echo "    token: ${TOKEN:0:24}..."

auth() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }

echo "[2] GET /auth/me ..."
auth "$BASE/auth/me" | python -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d;u=d['data'];print('    用户:',u['realName'],'角色:',u['roles'],'菜单数:',len(u['menus']))"

echo "[3] GET /system/users ..."
auth "$BASE/system/users" | python -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d;print('    用户数:',d['data']['total'])"

echo "[4] GET /system/depts ..."
auth "$BASE/system/depts" | python -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d;print('    科室数:',len(d['data']))"

echo "[5] GET /system/roles ..."
auth "$BASE/system/roles" | python -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d;print('    角色数:',len(d['data']))"

echo "[6] 未带 token 访问受保护接口应 401 ..."
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/system/users")
[ "$CODE" = "401" ] && echo "    OK (401)" || { echo "    FAIL: got $CODE"; exit 1; }

echo "全部通过 ✔"
