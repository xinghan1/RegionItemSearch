# 1.6.0 跨版本验证记录

验证日期：2026-10-06。兼容目标为 Java 版 1.8–26.3，包含容器搜索、玩家背包/末影箱搜索和在线/离线库存编辑。下表记录实际运行结果；适配范围不代表每个补丁版本、第三方分支构建均已逐一测试。

## 实际服务端

| 服务端 | Java | 原生物品、搜索与玩家存档 | 真实玩家库存 GUI |
| --- | --- | --- | --- |
| Spigot 1.8.8（3c4fa09-e1ebe52） | 8 | 35 项通过，含连续 80 次 1 MiB 自定义 NBT 解码 | 87 项通过：在线/离线背包及末影箱取放、取消、保存 |
| Spigot 1.12.2 | 8 | 35 项通过，含连续 80 次 1 MiB 自定义 NBT 解码 | 87 项通过 |
| Spigot 1.21.11（da5d877-249c293） | 21 | 30 项通过 | 87 项通过，使用 Java 21 |
| Leaf 1.21.11（158-dfd6281） | 21 | 35 项通过，含连续 80 次 1 MiB 自定义 NBT 解码 | 87 项通过 |
| Paper 26.3（157-4728a90） | 25 | 30 项通过 | 87 项通过，包含实际 `players/data` 目录 |
| Folia 26.2（7-14b7fee） | 25 | 30 项通过 | 84 项通过：跨区域取放、保存、退出/死亡取消及存档核对 |

原生检查使用当前服务端创建的真实 ItemStack。覆盖名称、Lore、附魔、MMOItems、自定义 NBT/数组的往返保留，旧金苹果变种，Region 与嵌套潜影盒搜索，玩家存档搜索、原字节备份、无关字段保留、旧快照拒绝写入和取消准备。

GUI 检查使用两名实际 TCP 连接玩家和服务端原生窗口，驱动服务端点击事件核对库存变化。Folia 玩家相距 4096 格，确认运行于不同实体所属区域线程。验证的是服务端库存行为，未人工逐一核对各版客户端界面的文字显示及鼠标操作。

## 自动检查与实现

- `mvn clean verify`：55 项测试通过；覆盖旧数字物品 ID/Damage、旧 Region 方块、旧玩家存档、组件解析、语言配置、命令、分页与并行读取等。
- 主代码以 Spigot 1.8.8 API 编译为 Java 8 字节码；打包检查未发现高于 Java 8 的基础类。聊天库及 NBT 依赖已打包并隔离。
- 26.1+ 的 `players/data` 与维度目录自动识别；目录变化见 [Minecraft 26.1 官方更新说明](https://feedback.minecraft.net/hc/en-us/articles/44551668333837-Minecraft-Java-Edition-26-1)。
- 1.8 无副手；旧版 UUID 点击填入聊天框，现代客户端支持复制剪贴板。
- 离线编辑要求玩家存档的 DataVersion 与当前服务器相同；1.8 允许没有 DataVersion 的原生存档。搜索仍可解析旧格式。
- Folia 使用实体/区域调度；跨区域保存有再次校验、条件回滚与失败恢复记录。正常退出在原生保存前恢复尚未确认的改动；库存转移尚未确认时发生死亡，先取消这一次死亡再恢复，避免未确认物品掉落。
- CraftBukkit 使用同一 Bukkit/NMS 适配路径；尚未使用独立 CraftBukkit 构建做真实服务端测试。中间补丁版本与其它第三方构建亦未全部实测。

## 复验文件

`src/test/java/` 为自动测试；`tests/runtime/PortableRuntimeProbe.java` 只核对编码、搜索与文件核心，明确跳过模拟玩家 GUI。`CommonTransferProbe.java` 和 `FoliaTransferProbe.java` 配合对应的 TCP 客户端脚本验证真实玩家菜单。探针仅用于独立 `compat-world` 测试服，不包含在正式插件 JAR 中。

插件构建需要 JDK 21+。服务器使用其版本要求的 Java：[Paper 官方 Java 版本表](https://docs.papermc.io/paper/getting-started/)规定 1.21.11 使用 Java 21，26.1+ 使用 Java 25。Folia 使用其实际已发布的版本；调度要求见 [Folia 官方开发说明](https://docs.papermc.io/paper/dev/folia-support/)。