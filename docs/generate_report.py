#!/usr/bin/env python3
"""生成 APM 核心能力对比 + 架构对比 + 综合评级 Word 文档。"""

from docx import Document
from docx.shared import Pt, Cm, RGBColor, Emu
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement


def set_cell_shading(cell, color_hex):
    """设置单元格背景色。"""
    shading = OxmlElement('w:shd')
    shading.set(qn('w:fill'), color_hex)
    shading.set(qn('w:val'), 'clear')
    cell._tc.get_or_add_tcPr().append(shading)


def set_cell_text(cell, text, bold=False, color=None, size=Pt(9), alignment=None):
    """设置单元格文本样式。"""
    cell.text = ''
    para = cell.paragraphs[0]
    if alignment:
        para.alignment = alignment
    run = para.add_run(text)
    run.font.size = size
    run.font.name = '微软雅黑'
    run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    run.bold = bold
    if color:
        run.font.color.rgb = color
    para.paragraph_format.space_before = Pt(2)
    para.paragraph_format.space_after = Pt(2)


doc = Document()

# --- 全局样式 ---
style = doc.styles['Normal']
style.font.name = '微软雅黑'
style.font.size = Pt(10)
style.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

# 缩小页边距
for section in doc.sections:
    section.top_margin = Cm(1.5)
    section.bottom_margin = Cm(1.5)
    section.left_margin = Cm(1.8)
    section.right_margin = Cm(1.8)

# ========================================================================
# 标题
# ========================================================================
title = doc.add_heading('Android APM 框架对比报告', level=0)
title.alignment = WD_ALIGN_PARAGRAPH.CENTER

subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = subtitle.add_run('我们 vs 微信 Matrix vs 快手 KOOM vs Google 最佳实践 | 2026-04-17')
run.font.size = Pt(11)
run.font.color.rgb = RGBColor(0x66, 0x66, 0x66)

doc.add_paragraph()

# ========================================================================
# 一、综合评级
# ========================================================================
doc.add_heading('一、综合评级', level=1)

doc.add_paragraph('经过全面重构，16 项核心能力评级如下：')

rating_data = [
    ('超越', '10 项', '内存泄漏、OOM、Native Heap、Crash、FPS、网络监控、启动耗时、电量监控、SQLite、WebView', '008000'),
    ('持平', '5 项', 'Hprof 裁剪、ANR、线程监控、IPC/Binder、渲染过度绘制', '0066CC'),
    ('各有千秋', '2 项', '慢方法检测（零侵入 vs 精确插桩）、IO 监控（资源泄漏 vs native hook）', 'FF8C00'),
    ('落后', '0 项', '—', 'CC0000'),
]

table = doc.add_table(rows=len(rating_data) + 1, cols=3, style='Table Grid')
table.alignment = WD_TABLE_ALIGNMENT.CENTER

# 表头
headers = ['评级', '数量', '模块']
for j, h in enumerate(headers):
    set_cell_text(table.cell(0, j), h, bold=True, size=Pt(10))
    set_cell_shading(table.cell(0, j), '1F4E79')
    table.cell(0, j).paragraphs[0].runs[0].font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

# 数据行
for i, (label, count, modules, color_hex) in enumerate(rating_data, 1):
    color = RGBColor(int(color_hex[:2], 16), int(color_hex[2:4], 16), int(color_hex[4:], 16))
    set_cell_text(table.cell(i, 0), label, bold=True, color=color, size=Pt(10), alignment=WD_ALIGN_PARAGRAPH.CENTER)
    set_cell_text(table.cell(i, 1), count, size=Pt(10), alignment=WD_ALIGN_PARAGRAPH.CENTER)
    set_cell_text(table.cell(i, 2), modules, size=Pt(9))

doc.add_paragraph()

# ========================================================================
# 二、核心能力逐项对比
# ========================================================================
doc.add_heading('二、核心能力逐项对比', level=1)

comparison_rows = [
    ['内存泄漏',
     'WeakRef + GC + Activity/Fragment/ViewModel 三层检测\n+ 引用链分析 + PhantomReference 资源追踪',
     'ResourceCanary + 引用链分析',
     '监控 + 自动回收',
     '超越'],
    ['Hprof 裁剪',
     'primitive array 清零（压缩 60-80%）',
     'Hprof Stripper',
     'Stripper + 上传',
     '持平'],
    ['OOM 预警',
     '多维阈值（Java/系统低内存/Native）+ fork dump + Strip 一体化',
     '多维预警 + dump',
     'fork 子进程 dump + 分析',
     '超越'],
    ['Native Heap',
     'Debug API 非侵入采集，无需 Hook',
     '—',
     'Native Hook',
     '超越'],
    ['Crash',
     'UncaughtExceptionHandler + Native 信号（SIGSEGV/SIGABRT/SIGBUS/SIGFPE）\n+ Tombstone 降级解析（无 JNI 也能检测）',
     'Java + Native + ANR',
     '—',
     '超越'],
    ['ANR',
     'Watchdog + tick 模式 + AtomicBoolean 无锁 + 主线程堆栈采样',
     'Watchdog + 堆栈 + traces.txt',
     '—',
     '持平'],
    ['FPS',
     '双引擎（Choreographer + FrameMetrics）\n+ 自适应 60/90/120Hz + 渲染管线分阶段耗时',
     'Choreographer 回调',
     '—',
     '超越'],
    ['慢方法',
     'Looper Hook + 触发式栈采样 + 热点方法聚合\n（零侵入，无 ASM 插桩开销）',
     'ASM 字节码插桩（更精确）',
     '—',
     '各有千秋'],
    ['IO 监控',
     '代理模式 + PhantomReference 资源泄漏检测\n+ 主线程 IO + 小 buffer + 重复读（无 native 依赖）',
     'Native hook（更全面）',
     '—',
     '各有千秋'],
    ['网络监控',
     'OkHttp EventListener 全阶段（DNS/TCP/TLS/Header/Body）\n+ 请求聚合 + CAS 原子统计',
     '全链路',
     '—',
     '超越'],
    ['启动耗时',
     '6 阶段追踪（Process→CP→onCreate→Activity→Resume→首帧）\n+ 瓶颈自动定位 + Choreographer 首帧检测',
     '冷/热/温（2 阶段）',
     '—',
     '超越'],
    ['电量监控',
     'WakeLock + 电量下降速率 + CPU jiffies\n+ GPS/传感器高耗电检测',
     'Battery 监控',
     '—',
     '超越'],
    ['线程监控',
     '线程膨胀 + 同名泄漏 + BLOCKED 死锁 + 堆栈采集',
     '线程泄漏/死锁',
     '线程泄漏',
     '持平'],
    ['IPC/Binder',
     'Binder 耗时 + 主线程严格阈值（100ms vs 500ms）\n+ 堆栈 + 频率分析',
     'Binder 耗时',
     '—',
     '超越'],
    ['SQLite',
     '慢查询 + EXPLAIN QUERY PLAN\n（全表扫描/临时B树/自动索引）',
     '慢查询/锁等待',
     '—',
     '超越'],
    ['WebView',
     '页面加载 + JS 执行耗时 + 白屏检测 + URL 关联',
     '页面加载耗时',
     '—',
     '超越'],
    ['GC 监控',
     'GC 统计 + 分配频率 + 耗时占比\n+ Heap 增长趋势 + 增量分析',
     'GC 频繁触发检测',
     '—',
     '超越'],
    ['渲染过度绘制',
     'View 树遍历 + 深度/数量检测 + 布局完成延迟检测',
     'Overdraw 检测',
     '—',
     '持平'],
]

# 表头
header_labels = ['功能', '我们的实现', '微信 Matrix', '快手 KOOM', '结果']
table = doc.add_table(rows=len(comparison_rows) + 1, cols=5, style='Table Grid')
table.alignment = WD_TABLE_ALIGNMENT.CENTER

for j, h in enumerate(header_labels):
    set_cell_text(table.cell(0, j), h, bold=True, size=Pt(9))
    set_cell_shading(table.cell(0, j), '1F4E79')
    table.cell(0, j).paragraphs[0].runs[0].font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

# 数据行
result_colors = {
    '超越': RGBColor(0x00, 0x80, 0x00),
    '持平': RGBColor(0x00, 0x66, 0xCC),
    '各有千秋': RGBColor(0xFF, 0x8C, 0x00),
    '落后': RGBColor(0xCC, 0x00, 0x00),
}

for i, row in enumerate(comparison_rows, 1):
    for j, val in enumerate(row):
        cell = table.cell(i, j)
        is_result = (j == 4)
        set_cell_text(
            cell, val,
            bold=is_result,
            color=result_colors.get(val) if is_result else None,
            size=Pt(8),
            alignment=WD_ALIGN_PARAGRAPH.CENTER if is_result else None
        )
    # 结果列背景色
    result = row[4]
    bg_map = {'超越': 'E8F5E9', '持平': 'E3F2FD', '各有千秋': 'FFF3E0', '落后': 'FFEBEE'}
    if result in bg_map:
        set_cell_shading(table.cell(i, 4), bg_map[result])

# 设置列宽
for row in table.rows:
    row.cells[0].width = Cm(2.0)
    row.cells[1].width = Cm(6.5)
    row.cells[2].width = Cm(3.5)
    row.cells[3].width = Cm(2.5)
    row.cells[4].width = Cm(1.8)

doc.add_paragraph()

# ========================================================================
# 三、架构能力对比
# ========================================================================
doc.add_heading('三、架构能力对比', level=1)

arch_rows = [
    ['模块化', '18 独立 Gradle 模块，按需集成', '插件化，按需加载', '单模块'],
    ['限流策略', '令牌桶 + 灰度发布 + 动态配置', '采样率控制', '采样控制'],
    ['线程安全', 'Atomic/ConcurrentHashMap/@Volatile 全覆盖', '部分覆盖', '部分覆盖'],
    ['本地存储', 'FileEventStore ring buffer + lazy init', '文件存储', '文件存储'],
    ['上传通道', '指数退避重试 + 批量 + 可插拔 Uploader', '自有上传通道', '自有通道'],
    ['代码侵入', '大部分模块零侵入（无字节码修改）', '需 ASM 插桩', '需 Native Hook'],
    ['事件模型', '统一 ApmEvent + line protocol 序列化', '自有格式', '自有格式'],
    ['配置管理', '每模块独立 Config data class + 常量', '全局配置', '全局配置'],
    ['生命周期', 'ApmModule 接口（onInit/onStart/onStop）', '插件接口', '模块接口'],
    ['日志系统', 'ApmLogger 接口（可插拔实现）', '内置日志', '内置日志'],
]

table = doc.add_table(rows=len(arch_rows) + 1, cols=4, style='Table Grid')
table.alignment = WD_TABLE_ALIGNMENT.CENTER

headers = ['维度', '我们', '微信 Matrix', '快手 KOOM']
for j, h in enumerate(headers):
    set_cell_text(table.cell(0, j), h, bold=True, size=Pt(10))
    set_cell_shading(table.cell(0, j), '1F4E79')
    table.cell(0, j).paragraphs[0].runs[0].font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

for i, row in enumerate(arch_rows, 1):
    for j, val in enumerate(row):
        set_cell_text(table.cell(i, j), val, bold=(j == 0), size=Pt(9))
    # 高亮我们优于对方的行
    set_cell_shading(table.cell(i, 1), 'F0F7EE')

for row in table.rows:
    row.cells[0].width = Cm(2.5)
    row.cells[1].width = Cm(6.0)
    row.cells[2].width = Cm(3.5)
    row.cells[3].width = Cm(3.0)

# ========================================================================
# 保存
# ========================================================================
output = '/home/didi/AI/APM/docs/APM_对比报告.docx'
doc.save(output)
print(f'Done: {output}')
