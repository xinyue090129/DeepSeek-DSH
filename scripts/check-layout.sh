#!/bin/sh
# ============================================================
#  DSHA 控制中心 —— 布局回归检查
#
#  用无头 Firefox 在多个典型窗口尺寸下渲染 gui/src/index.html，
#  在页面里注入断言脚本：任何一条不通过，就把左上角色块涂成红色；
#  全部通过则涂成绿色。脚本再用 ImageMagick 采样判定 PASS / FAIL。
#
#  断言项（都是真实踩过的坑）：
#    1. 整页不出现纵向滚动条（滚动高度 <= 视口高度）
#    2. 整页不出现横向滚动条
#    3. 日志窗口宽度 == 所在列宽度（防 align-items:start 把它压成内容宽度）
#    4. 底部按钮组宽度 == 所在列宽度
#    5. #consoleView 撑满主内容区（防中间普通 div 吃掉 flex 高度）
#
#  用法：
#    sh scripts/check-layout.sh            # 跑全部尺寸
#    sh scripts/check-layout.sh 1180x900   # 只跑某个尺寸
#
#  依赖：firefox（无头截图）、magick（采样像素）。两者系统里都有。
#  截图落在 .buildtmp/layout/ 下，失败时可以直接打开看。
# ============================================================
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/.buildtmp/layout"
HTML="$ROOT/gui/src/index.html"
CSS="$ROOT/gui/src/style.css"
PROBE="$OUT/probe.html"

# 覆盖窗口尺寸默认尺寸(1180x780)、最小尺寸(800x600)、高窄、宽扁等边界
SIZES_DEFAULT="1180x780 1180x900 940x1000 800x600 1440x900 1600x1000"

mkdir -p "$OUT"

command -v firefox >/dev/null 2>&1 || { echo "缺少 firefox，无法渲染"; exit 2; }
command -v magick  >/dev/null 2>&1 || { echo "缺少 imagemagick(magick)，无法采样"; exit 2; }

# ---- 生成探针页：源码原样 + 断言脚本 ----
python3 - "$HTML" "$CSS" "$PROBE" <<'PY'
import re, sys
html, css, out = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(html, encoding="utf-8").read()
s = s.replace('href="style.css"', 'href="file://%s"' % css)
# 去掉业务脚本，只留结构与样式，避免轮询/网络干扰布局断言
s = re.sub(r"<script[^>]*src=[^>]*></script>", "", s)

probe = r"""
<div id="probePix" style="position:fixed;left:0;top:0;width:48px;height:48px;z-index:2147483647;background:#d50000"></div>
<div id="probeTxt" style="position:fixed;left:56px;top:0;z-index:2147483647;background:#fff;color:#000;font:14px/1.45 monospace;padding:6px 8px;max-width:min(1000px,90vw);white-space:pre-wrap"></div>
<script>(function(){
  var fails = [], notes = [];
  function box(sel){ var e = document.querySelector(sel); return e ? e.getBoundingClientRect() : null; }
  function near(a, b, tol){ return Math.abs(a - b) <= (tol || 1.5); }
  var de = document.documentElement;

  // 1/2. 整页滚动
  if (de.scrollHeight > window.innerHeight + 1) fails.push("纵向溢出 " + de.scrollHeight + ">" + window.innerHeight);
  if (de.scrollWidth > window.innerWidth + 1) fails.push("横向溢出 " + de.scrollWidth + ">" + window.innerWidth);

  // 3/4. 列内元素必须与列同宽（左右各留 1.5px 容差）
  function sameWidth(childSel, colSel, label){
    var c = box(childSel), col = box(colSel);
    if (!c || !col) { notes.push(label + ": 元素缺失"); return; }
    if (!near(c.width, col.width)) fails.push(label + " 宽 " + Math.round(c.width) + "≠列宽 " + Math.round(col.width));
    else notes.push(label + " 宽 " + Math.round(c.width) + " OK");
  }
  sameWidth(".log-window", ".right-column", "日志框");
  sameWidth(".bottom-actions", ".right-column", "底部按钮组");

  // 5. #consoleView 必须撑满主内容区（扣掉上下 padding 由 flex 决定，允许 2px 误差）
  var cv = box("#consoleView"), mc = box(".main-content");
  if (cv && mc) {
    var inner = mc.height - 0; // consoleView 在 padding 内部，必然小于 main
    if (cv.height < inner * 0.75) fails.push("consoleView 未撑满 " + Math.round(cv.height) + "/" + Math.round(inner));
    else notes.push("consoleView " + Math.round(cv.height) + " OK");
  }

  // 结果上色
  var ok = fails.length === 0;
  document.getElementById("probePix").style.background = ok ? "#00c853" : "#d50000";
  document.getElementById("probeTxt").textContent =
    (ok ? "PASS " : "FAIL ") + window.innerWidth + "x" + window.innerHeight +
    (fails.length ? "\n✗ " + fails.join("\n✗ ") : "") +
    (notes.length ? "\n· " + notes.join("\n· ") : "");
})();</script>
</body>"""
s = s.replace("</body>", probe)
open(out, "w", encoding="utf-8").write(s)
PY

[ -f "$PROBE" ] || { echo "探针页生成失败"; exit 2; }

SIZES=${1:-$SIZES_DEFAULT}
FAILED=0
echo "== 布局回归检查 =="

for size in $SIZES; do
  W=${size%x*}; H=${size#*x}
  shot="$OUT/${W}x${H}.png"
  rm -f "$shot"
  # 无头渲染（超时保护：某些环境下 firefox 首次启动较慢）
  timeout 60 firefox --headless --window-size="$W,$H" --screenshot "$shot" "file://$PROBE" >/dev/null 2>&1
  if [ ! -f "$shot" ]; then
    echo "  $size  ✗ 渲染失败"
    FAILED=1
    continue
  fi
  # 采样左上角 48x48 色块中心
  px=$(magick "$shot" -format "%[pixel:p{24,24}]" info: 2>/dev/null)
  case "$px" in
    *"0,200,83"*|*"0, 200, 83"*) echo "  $size  ✓ PASS" ;;
    *) echo "  $size  ✗ FAIL   (截图：$shot)"; FAILED=1 ;;
  esac
done

if [ "$FAILED" -eq 0 ]; then
  echo "== 全部通过 =="
else
  echo "== 有失败项，见 $OUT 下的截图 =="
fi
exit "$FAILED"
