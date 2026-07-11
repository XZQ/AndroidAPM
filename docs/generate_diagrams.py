"""Generate the tracked AndroidAPM architecture SVG and PNG diagrams."""

from __future__ import annotations

from dataclasses import dataclass
from html import escape
from pathlib import Path
import subprocess
import tempfile
import time

OUTPUT_DIR = Path(__file__).resolve().parent / "architecture" / "generated-diagrams"
WIDTH = 1600
HEIGHT = 900
BROWSER_CANDIDATES = (
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
    Path.home() / r"AppData\Local\Google\Chrome\Application\chrome.exe",
)


@dataclass(frozen=True)
class Box:
    """A rounded architecture node."""

    x: int
    y: int
    width: int
    height: int
    title: str
    lines: tuple[str, ...] = ()
    color: str = "#1F5A94"
    fill: str = "#F4F8FC"


def box_svg(box: Box) -> str:
    """Render one node with a colored title and optional body lines."""
    title_y = box.y + 42
    body = []
    for index, line in enumerate(box.lines):
        body.append(
            f'<text x="{box.x + 24}" y="{box.y + 82 + index * 28}" '
            f'class="body">{escape(line)}</text>'
        )
    return (
        f'<rect x="{box.x}" y="{box.y}" width="{box.width}" height="{box.height}" '
        f'rx="18" fill="{box.fill}" stroke="{box.color}" stroke-width="3"/>'
        f'<rect x="{box.x}" y="{box.y}" width="10" height="{box.height}" '
        f'rx="5" fill="{box.color}"/>'
        f'<text x="{box.x + 24}" y="{title_y}" class="node-title" '
        f'fill="{box.color}">{escape(box.title)}</text>'
        + "".join(body)
    )


def arrow(x1: int, y1: int, x2: int, y2: int, label: str = "", dashed: bool = False) -> str:
    """Render a directed connection and optional centered label."""
    dash = ' stroke-dasharray="12 9"' if dashed else ""
    label_svg = ""
    if label:
        label_svg = (
            f'<rect x="{(x1 + x2) / 2 - 72}" y="{(y1 + y2) / 2 - 26}" '
            'width="144" height="30" rx="9" fill="#FFFFFF" opacity="0.94"/>'
            f'<text x="{(x1 + x2) / 2}" y="{(y1 + y2) / 2 - 5}" '
            f'class="label" text-anchor="middle">{escape(label)}</text>'
        )
    return (
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
        f'stroke="#52657A" stroke-width="4" marker-end="url(#arrow)"{dash}/>'
        + label_svg
    )


def pill(x: int, y: int, text: str, color: str = "#1F5A94") -> str:
    """Render a small legend pill."""
    width = max(150, len(text) * 18 + 32)
    return (
        f'<rect x="{x}" y="{y}" width="{width}" height="38" rx="19" '
        f'fill="{color}" opacity="0.12"/>'
        f'<text x="{x + width / 2}" y="{y + 25}" text-anchor="middle" '
        f'class="pill" fill="{color}">{escape(text)}</text>'
    )


def document(title: str, subtitle: str, elements: list[str]) -> str:
    """Wrap diagram elements in a consistent SVG canvas."""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}">
  <defs>
    <linearGradient id="background" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#F8FBFE"/>
      <stop offset="1" stop-color="#EEF4FA"/>
    </linearGradient>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="6" stdDeviation="8" flood-color="#12355B" flood-opacity="0.10"/>
    </filter>
    <marker id="arrow" markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto">
      <path d="M0,0 L12,6 L0,12 Z" fill="#52657A"/>
    </marker>
    <style>
      .title {{ font: 700 38px 'Microsoft YaHei', Arial, sans-serif; fill: #12355B; }}
      .subtitle {{ font: 400 20px 'Microsoft YaHei', Arial, sans-serif; fill: #52657A; }}
      .node-title {{ font: 700 23px 'Microsoft YaHei', Arial, sans-serif; }}
      .body {{ font: 400 18px 'Microsoft YaHei', Arial, sans-serif; fill: #2F4052; }}
      .label {{ font: 600 16px 'Microsoft YaHei', Arial, sans-serif; fill: #40556B; }}
      .pill {{ font: 600 16px 'Microsoft YaHei', Arial, sans-serif; }}
      .boundary {{ font: 700 18px 'Microsoft YaHei', Arial, sans-serif; fill: #9B5C17; }}
    </style>
  </defs>
  <rect width="{WIDTH}" height="{HEIGHT}" fill="url(#background)"/>
  <text x="70" y="70" class="title">{escape(title)}</text>
  <text x="70" y="108" class="subtitle">{escape(subtitle)}</text>
  <g filter="url(#shadow)">{''.join(elements)}</g>
  <text x="1530" y="865" text-anchor="end" class="subtitle">AndroidAPM · 2026-07-11</text>
</svg>'''


def overview() -> str:
    """Build the high-level client architecture diagram."""
    elements = [
        box_svg(Box(70, 210, 250, 150, "宿主应用", ("Application", "显式 API / 配置"), "#6D4AA5", "#F8F4FC")),
        box_svg(Box(390, 175, 340, 220, "监控与扩展模块", ("15 monitoring modules", "apm-trace", "apm-otel-exporter"), "#157A6E", "#F0FAF7")),
        box_svg(Box(800, 175, 300, 220, "apm-core", ("配置与生命周期", "ApmDispatcher", "ApmExecutors / health"), "#1F5A94")),
        box_svg(Box(1170, 175, 360, 220, "数据底座", ("apm-model", "apm-storage (SQLite outbox)", "apm-uploader"), "#B25D16", "#FFF8F1")),
        arrow(320, 285, 390, 285, "初始化/埋点"),
        arrow(730, 285, 800, 285, "事件"),
        arrow(1100, 285, 1170, 285, "批处理"),
        box_svg(Box(390, 510, 340, 150, "apm-plugin", ("AGP instrumentation", "慢方法构建期插桩"), "#6D4AA5", "#F8F4FC")),
        arrow(195, 360, 195, 585, "构建"),
        arrow(390, 585, 320, 585, "字节码"),
        box_svg(Box(1170, 510, 360, 150, "外部生产系统", ("Collector / 查询 / 告警", "本仓库不包含"), "#9B5C17", "#FFF8E8")),
        arrow(1350, 395, 1350, 510, "Transport", dashed=True),
        '<rect x="1125" y="455" width="450" height="250" rx="24" fill="none" stroke="#C77A25" stroke-width="3" stroke-dasharray="14 10"/>',
        '<text x="1150" y="692" class="boundary">客户端仓库边界之外</text>',
        pill(70, 765, "实线：仓库内依赖", "#1F5A94"),
        pill(350, 765, "虚线：外部集成边界", "#9B5C17"),
    ]
    return document("AndroidAPM 总体架构", "客户端采集、持久化与上传边界", elements)


def module_dependencies() -> str:
    """Build the main module dependency diagram."""
    elements = [
        box_svg(Box(70, 185, 260, 140, "Sample / Benchmark", ("apm-sample-app", "apm-benchmark (不发布)"), "#6D4AA5", "#F8F4FC")),
        box_svg(Box(395, 165, 330, 180, "监控模块 × 15", ("自动生命周期采集", "显式 API 采集", "构建期慢方法"), "#157A6E", "#F0FAF7")),
        box_svg(Box(790, 165, 300, 180, "apm-core", ("Apm / Dispatcher", "配置 / 执行器", "内部健康"), "#1F5A94")),
        box_svg(Box(1155, 145, 375, 220, "数据底座", ("apm-model", "apm-storage", "apm-uploader", "eventId + claim lease"), "#B25D16", "#FFF8F1")),
        arrow(330, 255, 395, 255, "依赖/演示"),
        arrow(725, 255, 790, 255, "上报"),
        arrow(1090, 255, 1155, 255, "存储/上传"),
        box_svg(Box(395, 500, 330, 160, "扩展模块 × 2", ("apm-trace", "apm-otel-exporter"), "#4C6A92", "#F3F6FA")),
        arrow(560, 500, 900, 345, "复用核心"),
        box_svg(Box(70, 510, 260, 140, "apm-plugin", ("included build", "AGP 插桩插件"), "#6D4AA5", "#F8F4FC")),
        arrow(200, 510, 200, 325, "应用构建"),
        box_svg(Box(790, 500, 300, 160, "build-logic", ("included build", "约定插件 / 发布元数据"), "#52657A", "#F4F6F8")),
        arrow(940, 500, 940, 345, "构建约定", dashed=True),
        pill(70, 765, "箭头指向被调用/依赖侧"),
        pill(430, 765, "uploader 不反向依赖 core", "#B25D16"),
    ]
    return document("AndroidAPM 模块依赖", "23 个根子项目 + 2 个 included builds", elements)


def event_pipeline() -> str:
    """Build the durable event delivery diagram."""
    elements = [
        box_svg(Box(60, 220, 250, 170, "事件来源", ("监控回调", "显式 API", "插桩计时"), "#157A6E", "#F0FAF7")),
        box_svg(Box(365, 220, 250, 170, "Dispatcher", ("有界队列", "批量出队", "调用方非阻塞"), "#1F5A94")),
        box_svg(Box(670, 220, 250, 170, "SQLite outbox", ("unique eventId", "claim / lease / expiry", "进程重启恢复"), "#B25D16", "#FFF8F1")),
        box_svg(Box(975, 220, 250, 170, "Upload worker", ("owner-aware ACK", "Retry-After", "非阻塞退避"), "#6D4AA5", "#F8F4FC")),
        box_svg(Box(1280, 220, 260, 170, "Transport", ("可注入实现", "外部 collector", "仓库外系统"), "#9B5C17", "#FFF8E8")),
        arrow(310, 305, 365, 305),
        arrow(615, 305, 670, 305),
        arrow(920, 305, 975, 305),
        arrow(1225, 305, 1280, 305),
        arrow(1410, 390, 795, 560, "成功确认", dashed=True),
        arrow(1100, 390, 1100, 610, "失败/限流", dashed=True),
        box_svg(Box(670, 560, 250, 120, "删除已确认批次", ("ack 后 delete",), "#157A6E", "#F0FAF7")),
        box_svg(Box(975, 610, 250, 120, "保留并重试", ("至少一次交付",), "#B25D16", "#FFF8F1")),
        pill(60, 770, "已实现：持久化恢复", "#157A6E"),
        pill(360, 770, "未保证：exactly-once", "#B25D16"),
        pill(700, 770, "已实现：claim / lease", "#157A6E"),
    ]
    return document("AndroidAPM 事件管线", "acknowledged at-least-once；成功确认后删除", elements)


def monitoring_modules() -> str:
    """Group modules by their real integration model."""
    elements = [
        box_svg(Box(70, 170, 450, 480, "自动生命周期 / 系统采集", (
            "apm-memory · apm-crash", "apm-anr · apm-launch", "apm-fps · apm-gc-monitor", "apm-render · apm-thread-monitor", "", "初始化后可运行", "仍受 API / 权限 / OEM 限制"
        ), "#157A6E", "#F0FAF7")),
        box_svg(Box(575, 170, 450, 480, "宿主显式 API 接入", (
            "apm-network · apm-sqlite", "apm-ipc · apm-webview", "apm-battery · apm-io", "thread pool registration", "", "宿主传入真实调用点数据", "不等于全局自动 Hook"
        ), "#1F5A94", "#F4F8FC")),
        box_svg(Box(1080, 170, 450, 230, "构建期插桩", (
            "apm-slow-method", "apm-plugin", "", "需显式应用 Gradle 插件"
        ), "#6D4AA5", "#F8F4FC")),
        box_svg(Box(1080, 445, 450, 205, "扩展模块", (
            "apm-trace", "apm-otel-exporter", "进程内 span / 事件映射"
        ), "#B25D16", "#FFF8F1")),
        pill(70, 750, "配置字段不是能力证明", "#9B5C17"),
        pill(410, 750, "以代码路径和测试为准", "#1F5A94"),
    ]
    return document("AndroidAPM 监控模块", "按真实接入方式分组，而非按营销口径分组", elements)


def slow_method() -> str:
    """Build the slow-method build/runtime flow diagram."""
    elements = [
        box_svg(Box(70, 245, 250, 180, "宿主 Gradle 配置", ("应用 apm-plugin", "包过滤", "阈值与开关"), "#6D4AA5", "#F8F4FC")),
        box_svg(Box(385, 245, 250, 180, "AGP Instrumentation", ("ClassVisitor", "MethodVisitor", "现代 instrumentation API"), "#1F5A94")),
        box_svg(Box(700, 245, 250, 180, "插桩后字节码", ("方法入口计时", "正常/异常出口", "保持原行为"), "#157A6E", "#F0FAF7")),
        box_svg(Box(1015, 245, 250, 180, "运行时计时", ("阈值判断", "线程/方法信息", "慢方法事件"), "#B25D16", "#FFF8F1")),
        box_svg(Box(1330, 245, 210, 180, "事件管线", ("Apm", "Dispatcher", "outbox"), "#1F5A94")),
        arrow(320, 335, 385, 335, "配置"),
        arrow(635, 335, 700, 335, "访问"),
        arrow(950, 335, 1015, 335, "执行"),
        arrow(1265, 335, 1330, 335, "上报"),
        box_svg(Box(385, 560, 565, 120, "构建期", ("只处理匹配类；插件未应用则不插桩",), "#6D4AA5", "#F8F4FC")),
        box_svg(Box(1015, 560, 525, 120, "运行期", ("超过阈值才形成事件；仍受采样与配置约束",), "#B25D16", "#FFF8F1")),
        pill(70, 770, "非 legacy Transform"),
        pill(390, 770, "非全局零侵入", "#9B5C17"),
    ]
    return document("AndroidAPM 慢方法插桩", "构建期改写字节码，运行时只上报命中事件", elements)


def write_diagram(stem: str, svg: str) -> None:
    """Write SVG and render a high-resolution PNG counterpart with Chromium."""
    svg_path = OUTPUT_DIR / f"{stem}.svg"
    png_path = OUTPUT_DIR / f"{stem}.png"
    svg_path.write_text(svg, encoding="utf-8")
    browser = next((path for path in BROWSER_CANDIDATES if path.exists()), None)
    if browser is None:
        raise RuntimeError("Microsoft Edge or Google Chrome is required to render PNG diagrams")
    # Chromium on Windows hands screenshot work to a child process after the
    # launcher exits. A persistent headless-only profile keeps that child alive
    # long enough to finish; the rendered temporary file remains in docs only
    # until it has been copied to the tracked target.
    profile = Path(tempfile.gettempdir()) / "android-apm-headless-profile"
    profile.mkdir(parents=True, exist_ok=True)
    temporary_png = OUTPUT_DIR / f"{stem}.render.png"
    temporary_png.unlink(missing_ok=True)
    completed = subprocess.run(
        [
            str(browser),
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--no-first-run",
            "--no-default-browser-check",
            "--force-device-scale-factor=1",
            f"--window-size={WIDTH},{HEIGHT}",
            f"--user-data-dir={profile}",
            f"--screenshot={temporary_png}",
            svg_path.resolve().as_uri(),
        ],
        check=True,
        timeout=30,
        capture_output=True,
        text=True,
    )
    deadline = time.monotonic() + 10
    while not temporary_png.exists() and time.monotonic() < deadline:
        time.sleep(0.1)
    if not temporary_png.exists():
        raise RuntimeError(
            f"Browser did not create {temporary_png}; "
            f"stdout={completed.stdout!r}; stderr={completed.stderr!r}"
        )
    png_path.write_bytes(temporary_png.read_bytes())
    temporary_png.unlink()
    print(svg_path)
    print(png_path)


def main() -> None:
    """Generate every tracked architecture diagram."""
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    diagrams = {
        "android-apm-overview": overview(),
        "android-apm-module-dependencies": module_dependencies(),
        "android-apm-event-pipeline": event_pipeline(),
        "android-apm-monitoring-modules": monitoring_modules(),
        "android-apm-slow-method-instrumentation": slow_method(),
    }
    for stem, svg in diagrams.items():
        write_diagram(stem, svg)


if __name__ == "__main__":
    main()
