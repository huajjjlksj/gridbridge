# GridBridge (gridbridge)

**CEE 双闸开关 ↔ PowerGrid 双向电网桥** —— Minecraft 1.21.1 NeoForge 附属模组

让 [Create: Electro Energetics (CEE)](https://www.curseforge.com/minecraft/mc-mods/create-electro-energetics) 的**双闸开关（double switch）**可以直接接入 [Create: PowerGrid (PG)](https://www.curseforge.com/minecraft/mc-mods/power-grid) 的电网，两个独立电力仿真器之间实现**双向电压/电流转换（1:1）**。

> ⚠️ **AI 生成声明**：本模组由 AI 助手（Nous Research Hermes Agent）辅助编写——包括架构设计、源代码实现、调试与构建流程。详见 [LICENSE.md](LICENSE.md)。

## 功能

- **PG 电线直接连接 CEE 双闸开关**：4 个接线端子与 CEE 节点位置完全一致，悬停显示 PG 同款灰色待连接虚框（TerminalHandler 渲染）
- **双向电力转换（1:1）**：
  - CEE → PG：CEE 电源供电 PG 设备（电动机等）
  - PG → CEE：PG 电源供电 CEE 设备（电压经有限能量源注入 CEE 节点）
- **开关语义保留**：双闸开关断开 → 两侧完全隔离；闭合 → 功率双向流动
- **破坏联动**：闸刀被破坏/爆炸/区块卸载 → 连接的 PG 电线一起断开（CleanupBehaviour 钩子）
- **过流保护**：开关支路电流超阈值自动熔断（防同根线短路烧毁），回落自动复位

## 使用

1. 放置 CEE **双闸开关**（右键切换开合）
2. 用 PG 电线（轻线/重线）点击开关端子 → 连到 PG 电网
3. 用 CEE 电线连接闸刀的 CEE 节点（接 CEE 电源/设备）
4. 合闸后两侧电压 1:1，功率自动双向流动

## 构建

依赖：NeoForge 1.21.1 + CEE 1.1.1 + PowerGrid 0.5.5.1 + Create 6.0.10（编译期依赖，libs/ 下 flatDir）。

```bash
bash offline-build.sh   # 离线 javac 构建（跳过 NeoForm 管道）
# 产物: build/libs/gridbridge-1.0.0.jar
```

## 许可证

[MIT License](LICENSE.md) —— 含 AI 生成声明。
