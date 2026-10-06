# RegionItemSearch

面向 Java 版 **1.8–26.3** 的跨版本物品搜索插件，适配 Spigot、CraftBukkit、Paper、Leaf 及已发布的 Folia 版本。直接读取已保存的 Region 和玩家数据，不加载区块；保留嵌套潜影盒、MMOItems 查询、聊天分页、坐标传送及在线/离线库存编辑，无需 MMOItems 前置。兼容范围与实测结果见下方。

## 安装

将 `RegionItemSearch-1.6.0.jar` 放入服务器 `plugins/`，替换旧版后重启。首次启动自动生成 `plugins/RegionItemSearch/config.yml` 和 `lang.yml`。

## 版本兼容

一个 JAR，插件字节码兼容 Java 8；**服务器本身仍需使用其版本要求的 Java**，例如 Paper 1.21.11 用 Java 21，26.1+ 用 Java 25。Folia 使用实体/区域调度器，不读取其它玩家线程的库存。

- 1.8–1.12：解析旧物品 ID、Damage 变种、数字附魔与旧区块格式；1.8 没有副手槽。
- 1.13–1.20.4：支持扁平化材质 ID 与传统 tag 物品数据。
- 1.20.5–26.3：支持 Data Components；保留物品的原生 NBT 和自定义数据。
- 26.1+ 自动识别维度目录及 `players/data` 玩家存档；旧版使用 `playerdata`。
- 旧版客户端不支持点击复制剪贴板时，点击 UUID 会填入聊天输入框，供手动复制。
- 库存写回只允许与当前服务端 DataVersion 相同的存档；先让服务器升级旧玩家数据，再编辑。
- Folia 跨区域保存使用条件提交和回滚；无法安全回滚时会记录到 `recovery-journal/` 并报告错误。

此范围是适配目标，不能等同于所有服务端构建均已逐个验证；实际验证记录见文末。

## 权限表

| 权限 | 作用 | 默认 |
| --- | --- | --- |
| `itemsearch.admin` | 所有搜索、帮助、分页、状态、停止、重载及坐标传送 | OP |
| `itemsearch.edit` | 打开与保存玩家背包/末影箱 GUI；同时需要 `itemsearch.admin` | OP |

## 完整指令表

`[]` 为可选参数，`<>` 为必填参数。省略查询关键词时使用执行者主手物品；控制台查询必须填写关键词，不能执行 `same`、传送或打开 GUI。

### 容器查询与管理

| 指令 | 作用 | 所需权限 |
| --- | --- | --- |
| `/itemsearch` 或 `/itemsearch help` | 查看帮助 | `itemsearch.admin` |
| `/itemsearch same` | 精确查询主手物品的稳定特征 | `itemsearch.admin` |
| `/itemsearch name [exact\|fuzzy] [关键词]` | 查询物品名称 | `itemsearch.admin` |
| `/itemsearch lore [exact\|fuzzy] [any\|all] [关键词]` | 查询物品 Lore | `itemsearch.admin` |
| `/itemsearch id [exact\|fuzzy] [ID或TYPE/ID]` | 查询 MMOItems 身份 | `itemsearch.admin` |
| `/itemsearch material [exact\|fuzzy] [Material]` | 查询原版物品类型，如 `FIREWORK_STAR` | `itemsearch.admin` |
| `/itemsearch results [页码]`<br>`/itemsearch results <结果UUID> <页码>` | 查看最近一次容器查询结果，默认第 1 页 | `itemsearch.admin` |
| `/itemsearch status` | 查看容器查询进度 | `itemsearch.admin` |
| `/itemsearch stop` | 停止容器查询，保留已找到的结果 | `itemsearch.admin` |
| `/itemsearch reload` | 重载配置与语言文件；扫描配置用于下次查询 | `itemsearch.admin` |
| `/itemsearch tp <结果UUID> <命中编号>` | 传送到对应容器附近；请点击聊天坐标自动执行，仅玩家可用 | `itemsearch.admin` |

### 玩家背包与末影箱

| 指令 | 作用 | 所需权限 |
| --- | --- | --- |
| `/itemsearch player` 或 `/itemsearch player help` | 查看玩家查询帮助 | `itemsearch.admin` |
| `/itemsearch player same` | 查询主手物品的稳定特征 | `itemsearch.admin` |
| `/itemsearch player name [exact\|fuzzy] [关键词]` | 查询物品名称 | `itemsearch.admin` |
| `/itemsearch player lore [exact\|fuzzy] [any\|all] [关键词]` | 查询物品 Lore | `itemsearch.admin` |
| `/itemsearch player id [exact\|fuzzy] [ID或TYPE/ID]` | 查询 MMOItems 身份 | `itemsearch.admin` |
| `/itemsearch player material [exact\|fuzzy] [Material]` | 查询原版物品类型 | `itemsearch.admin` |
| `/itemsearch player results [页码]`<br>`/itemsearch player results <结果UUID> <页码>` | 查看最近一次玩家查询结果，默认第 1 页 | `itemsearch.admin` |
| `/itemsearch player status` | 查看玩家查询进度 | `itemsearch.admin` |
| `/itemsearch player stop` | 停止玩家查询，保留已找到的结果 | `itemsearch.admin` |
| `/itemsearch player inventory <玩家UUID>` | 打开在线/离线玩家背包 GUI，仅玩家可用 | `itemsearch.admin` + `itemsearch.edit` |
| `/itemsearch player ender <玩家UUID>` | 打开在线/离线玩家末影箱 GUI，仅玩家可用 | `itemsearch.admin` + `itemsearch.edit` |

`players` 可替代所有 `player` 子命令；容器与玩家查询的 `results` 均可写为 `page`。带结果 UUID 的分页格式由聊天按钮自动使用，防止旧按钮操作新结果。页码及命中编号从 **1** 开始；传送命中编号可能与汇总后的容器行号不同，由坐标按钮生成。玩家 UUID 必须完整。

### 匹配规则与示例

| 模式/选项 | 规则 |
| --- | --- |
| `same` | 精确比较类型、名称、Lore、模型、MMOItems 身份、附魔、属性、PDC 等稳定特征；忽略数量、槽位、耐久及配置的动态字段，不接受关键词 |
| `name` / `lore` | 去除颜色、转纯文本后匹配；默认 `fuzzy` |
| `id` | MMOItems 的 `TYPE/ID`；手持时同时比较两者，输入裸 ID 时不限 TYPE，默认 `fuzzy` |
| `material` | 原版物品类型；手持时精确匹配，直接输入时默认 `fuzzy`，支持 `minecraft:` 前缀 |
| `exact` / `fuzzy` | 精确匹配 / 忽略大小写的包含匹配 |
| `all` / `any` | Lore 所有模板行匹配 / 任意一行匹配，默认 `all` |

选项写在关键词前；Lore 的匹配选项与 `all/any` 顺序可交换。关键词以选项词开头时先写 `--`。

```text
/itemsearch name 亡灵
/itemsearch id exact SWORD/WLZR
/itemsearch player material exact FIREWORK_STAR
/itemsearch lore -- all
```

## 搜索范围与报告

容器查询读取配置世界的 `.mca` / `.mcc`，离线玩家查询读取 `.dat`，在线玩家使用分批捕获的库存快照。未保存的改动可能查不到；需要先保存时手动执行服务器命令 `/save-all flush`。扫描期间仍可能发生保存，结果不代表所有文件在同一时刻的状态。

未生成的战利品、掉落物和实体背包不在容器查询结果中；读取错误、停止、数量或嵌套深度达到上限时，结果可能不完整。**扫描不加载区块，主动点击坐标传送会加载目标区块。**

文本报告保存在 `plugins/RegionItemSearch/reports/` 和 `player-reports/`。聊天分页在重启后失效，同类新查询完成后旧分页及坐标按钮过期。

源码构建需要 **JDK 21+**：`mvn clean verify`；生成的插件仍为 Java 8 字节码。1.6.0 已通过 55 项自动测试；真实服务端验证记录见 [兼容性验证](tests/COMPATIBILITY.md)。未逐个测试所有补丁版本及客户端视觉表现。

