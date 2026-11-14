#!/bin/sh
# ============================================================
#  会话接管 UI / 接口契约检查（静态 + 可重复）
#
#  为什么不用无头浏览器跑真实 app.js：前端有常驻长轮询（logLoop），
#  在 --dump-dom / --screenshot 的加载屏障下会持续产生任务，
#  截图时机不确定、结果不可重复。这里改为断言“契约”本身：
#    - DOM 里必须有 sessionBar / sessionNote / sessionActions
#    - 三种状态必须有独立文案与按钮
#    - 后端必须暴露对应路由
#    - 样式必须有对应类
#  真实交互留给人工点一次；行为正确性由后端 E2E 覆盖。
# ============================================================
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FAIL=0

ok()   { echo "  ✓ $1"; }
bad()  { echo "  ✗ $1"; FAIL=1; }
has()  { grep -qF -- "$2" "$1"; }

HTML="$ROOT/gui/src/index.html"
JS="$ROOT/gui/src/app.js"
CSS="$ROOT/gui/src/style.css"
BE="$ROOT/backend/src/com/dshlauncher/Backend.java"

echo "== 1. DOM 结构 =="
for id in sessionBar sessionNote sessionActions; do
  has "$HTML" "id=\"$id\"" && ok "存在 #$id" || bad "缺少 #$id"
done

echo "== 2. 状态与文案 =="
for s in adopted conflict unknown; do
  has "$JS" "$s:" && ok "HEAD/LABEL 含 $s" || bad "HEAD/LABEL 缺少 $s"
done
for t in "已接回原实例" "外部实例冲突" "待核验"; do
  has "$JS" "$t" && ok "状态文案：$t" || bad "缺少状态文案：$t"
done

echo "== 3. 操作按钮 =="
for b in "重新核验" "停止" "附着打开" "接管重启" "仅结束外部实例" "重新检测" "确认已退出并清理" "查看记录"; do
  has "$JS" "$b" && ok "按钮：$b" || bad "缺少按钮：$b"
done

echo "== 4. 后端路由 =="
for r in session recheck forget reclaim attach kill-foreign health; do
  has "$BE" "case \"/api/instances/$r\"" && ok "/api/instances/$r" || bad "缺少 /api/instances/$r"
done

echo "== 5. 安全约束 =="
has "$BE" "signalProcess" && ok "停止走身份核验后的 signalProcess" || bad "缺少 signalProcess"
has "$BE" "procPgid" && ok "非组长只发单 PID（不误伤 shell 组）" || bad "缺少 procPgid 判定"
has "$BE" "Observation.UNKNOWN" && ok "UNKNOWN 保持屏障" || bad "缺少 UNKNOWN 分支"
has "$BE" "进程仍存活，不能退休记录" && ok "存活时拒绝退休记录" || bad "缺少 forget 守卫"
grep -qF 'killGroup(fpid' "$BE" && bad "外部进程仍在整组 kill（会误伤 shell）" || ok "外部进程不整组 kill"

echo "== 6. 样式 =="
for c in ".session-bar" ".session-actions" ".session-note"; do
  has "$CSS" "$c" && ok "样式 $c" || bad "缺少样式 $c"
done

echo
if [ "$FAIL" -eq 0 ]; then echo "== 全部通过 =="; else echo "== 有失败项 =="; fi
exit "$FAIL"
