# HorizonFix

面向 GTNH（1.7.10）混合服务端（Crucible / Thermos）的服务端修复与防护模组合集。

- **纯服务端运行**：`acceptableRemoteVersions = "*"`，客户端无需安装。
- **模块化开关**：所有功能均可在 `config/HorizonFix.cfg` 中独立开关，关闭后对应监听器与 Mixin 不加载，无额外运行开销。
- **无侵入回滚**：不修改存档数据，移除 jar 即完全回滚。
- **前置依赖**：运行期需具备 Mixin 环境（GTNH 2.9.0+ 自带的 UniMixins 已包含）。

> 自动重生与 0 血修正位于 Bukkit 插件侧，由独立仓库 [HorizonFixPlugin](https://github.com/Huaxidesu/HorizonFixPlugin) 提供。

---

## 兼容性与更新摘要

| 版本 | 整合包兼容性 | 主要变更 |
|---|---|---|
| **1.8.0**（最新） | GTNH 2.9.1+（默认）<br>GTNH 2.9.0（可选） | 新增 Crucible 传送门区块加载死锁修复（`crucible.portalFix`），解决 Hodgepodge `preventEntityChunkLoading=true` 下进入地狱门卡死主线程的问题 |
| **1.7.0** | 同上 | 补齐「先放存储总线、再放抽屉管理器」反方向爆炸判定；默认污染量提高至 `30000000` |
| **1.6.1** | 同上 | 修订存储总线爆炸播报文案与替代方案指引 |
| **1.6.0** | 同上 | 存储总线与抽屉管理器结合时触发立即解体爆炸与区块污染（`ae2.explodeOnPlace`） |
| **1.5.0** | 同上 | 禁止 AE2 存储总线读取 Storage Drawers 抽屉管理器（`ae2.denyDrawerController`） |
| **1.4.0** | 同上 | 正式适配 GTNH 2.9.1+；因上游 Hodgepodge 2.7.223+ 已内置分割徽章修复，默认关闭 `extrautils.divisionSigil` |
| **1.3.1 及以下** | GTNH 2.9.0 | 墓碑落点修正、位置提醒、失败自动返还与被破坏防复制、被取消死亡快照清理、无线红石 NPE 补建、第三方模组 Mixin 适配 |

> **GTNH 2.9.0 用户提示**：若服务端运行于 GTNH 2.9.0（Hodgepodge 未内置分割徽章修复），请在 `config/HorizonFix.cfg` 中将 `extrautils.divisionSigil` 设为 `true`。

---

## 功能模块

| 模块 | 解决的问题 | 配置节 |
|---|---|---|
| **Crucible 传送门死锁修复** | 修复 Crucible 绕过 Hodgepodge `findingSpawnPoint` 保护导致的传送门卡死 | `crucible` |
| **AE2 存储总线与抽屉防护** | 禁止存储总线轮询抽屉管理器导致 TPS 下降，并在放置时触发爆炸惩罚 | `ae2` |
| **OpenBlocks 墓碑落点修正** | 避免墓碑生成在液体中，或在区块未加载时放置失败丢物品 | `grave` |
| **墓碑位置追踪** | 放置时、登录时提醒未取走的墓碑坐标，提供 `/graves` 查询命令 | `graveTracker` |
| **墓碑失败自动返还** | 墓碑未生成时自动回收地面掉落物并返还玩家背包（防爆炸/凋零破坏复制） | `graveRecovery` |
| **被取消死亡快照清理** | 清理免疫死亡（如吸血鬼）后 OpenBlocks 误生成的 `-death-` 背包快照 | `deathSnapshot` |
| **无线红石 NPE 修复** | 切维度或登录前补建 WR-CBE Addons 缺失的玩家附加数据，防止崩服 | `wirelessredstone` |
| **第三方模组适配** | 修复 ExtraUtilities 垃圾桶/分割徽章、Botania 特殊花、MrTJPCore 区块集合问题 | `extrautils` / `botania` / `mrtjp` |
| **级联世界生成诊断** | 记录区块填充期间触发相邻区块加载的调用来源，用于排查卡顿源 | `worldgen` |

---

## 核心修复说明

### 1. Crucible 传送门死锁修复（`crucible`）

Hodgepodge 的 `preventEntityChunkLoading=true` 会在实体 tick 期间阻止区块加载以优化 TPS，其自带的 `ServerConfigurationManager_FixTeleporter` 仅在 Forge 原版 `transferEntityToWorld` 期间临时设置 `destination.findingSpawnPoint = true` 放行加载。

Crucible/Bukkit 混合服务端走的是三参数重载 `transferPlayerToDimension(EntityPlayerMP, int, PlayerTeleportEvent$TeleportCause)`，绕过了 Hodgepodge 的保护，导致 `Teleporter.findPortal` 搜索传送门时被 EntityGuard 拦截并触发主线程死锁。

- **实现**：`MixinCruciblePortalFix` 在检测到 `org.bukkit.Bukkit` 时加载，在 `transferPlayerToDimension` 入口临时设置 `destination.findingSpawnPoint = true`，返回时恢复原值。

### 2. AE2 存储总线与抽屉管理器（`ae2`）

AE2 存储总线贴在抽屉管理器（`TileEntityController` / `TileEntitySlave`）上时，每次轮询都会遍历所有抽屉并完整复制带有 NBT 的 `ItemStack`，抽屉越多 TPS 越低。

- **静默拦截**（`MixinAe2ExternalStorageRegistry`）：在 `ExternalStorageRegistry.getHandler` 返回恒空库存（避免返回 `null` 导致每 tick 重建缓存），使存储总线进入休眠，并向 8 格内玩家发送提示。启动时会进行一次功能自检，日志输出 `[HorizonFix] 存储总线拦截已生效`。
- **双向放置爆炸**（`MixinAe2CableBusPlacement` + `DrawerControllerPlaceGuard`）：无论「先放管理器再贴总线」还是「先放总线再放管理器」，都会立即清除抽屉管理器、生成爆炸（默认强度 `4.0`）、通过反射向所在区块追加 GregTech 污染（默认 `30000000`），并全服播报坐标与放置者。读档重建时 `player == null`，不会误炸已有结构。

### 3. OpenBlocks 墓碑系列（`grave` / `graveTracker` / `graveRecovery` / `deathSnapshot`）

- **落点修正**：监听 `GraveSpawnEvent`，在原落点位于液体中或 `hasLocation() == false` 时同步加载所在区块并按曼哈顿距离重选安全坐标；同一玩家同一 tick 只允许生成一座墓碑，避免重复掉落头颅。
- **位置追踪**：记录坐标至 `config/HorizonFix-graves.txt`，放置与登录时提醒玩家，支持 `/graves` 查看未取走墓碑（不主动加载远处区块）。
- **失败返还**：在 `GraveSpawnEvent` 后 2 tick 校验方块。仅当 `Placed=false` 确认放置失败时，移除地面掉落物、返还物品（离线或死亡界面暂存至 `config/HorizonFix-returns.dat`）并删除本次失败的 `-grave-` 备份；若墓碑生成后被爆炸或凋零摧毁（`Placed=true`），则不返还以杜绝复制。
- **快照清理**：在 `LivingDeathEvent` 早期记录快照目录，若死亡事件被取消（如 Witchery 吸血鬼），删除本次新增的 `-death-` 快照，防止 `/ob_inventory restore` 刷物品。

### 4. 无线红石 NPE 修复（`wirelessredstone`）

在 `PlayerLoggedInEvent` 与 `PlayerChangedDimensionEvent` 以 `HIGHEST` 优先级通过反射检查 WR-CBE Addons 的 `playerInfos` 表，缺失时提前调用 `onLogin(player)` 补建，避免切维度触发 `deactivateRemote` 空指针崩溃。

### 5. 第三方模组 Mixin 适配

通过 `MixinPlugin` 按目标模组存在性与配置开关条件加载：
- `ExtraUtilities`：垃圾桶读档后重扫库存（`trashCan`）；混合服务端分割徽章仪式、右键附魔台/信标、围城判定修复（`divisionSigil`，GTNH 2.9.1+ 默认关闭）。
- `Botania`：特殊花在无 TileEntity 位置查询亮度时防御 NPE（`specialFlower`）。
- `MrTJPCore`：活跃区块集合仅加入已加载区块，防止触发额外区块载入（`blockUpdate`）。

---

## 配置说明

配置文件：`config/HorizonFix.cfg`（首次启动自动生成）。

<details>
<summary>点击展开完整配置项说明</summary>

### `crucible`
| 键 | 默认值 | 说明 |
|---|---|---|
| `portalFix` | `true` | 在 Crucible 的 `transferPlayerToDimension` 期间临时启用 `findingSpawnPoint`，修复与 Hodgepodge `preventEntityChunkLoading=true` 冲突导致的传送门死锁 |

### `ae2`
| 键 | 默认值 | 说明 |
|---|---|---|
| `denyDrawerController` | `true` | 禁止存储总线读取抽屉管理器及其从站 |
| `denyDrawers` | `false` | 是否同时禁止读取单个抽屉方块 |
| `notifyPlayers` | `true` | 拦截生效时向附近玩家发送聊天提示 |
| `explodeOnPlace` | `true` | 存储总线与抽屉管理器以任意顺序结合时立即引爆并释放污染 |
| `explosionPower` | `4.0` | 爆炸强度（`0` 表示只清除方块不爆炸） |
| `explosionPollution` | `30000000` | 追加到所在区块的 GT 污染量（未安装 GT 时忽略） |

### `grave`
| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑落点修正总开关 |
| `avoidLiquid` | `true` | 落点位于液体内时重新选点 |
| `rescueMissing` | `true` | 未找到落点时自动补选坐标 |
| `singlePerTick` | `true` | 同一玩家同一 tick 只生成一座墓碑 |
| `searchRadius` | `8` | 选点搜索半径（方块） |
| `requireSupport` | `true` | 第一轮搜索要求下方为实体方块 |
| `diagnosticLimit` | `16` | 单次启动输出的墓碑诊断日志条数上限（`0` 关闭） |

### `graveTracker`
| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑追踪与 `/graves` 命令总开关 |
| `notifyOnPlace` | `true` | 放置时聊天栏提示坐标 |
| `notifyOnLogin` | `true` | 登录时提醒未取走的墓碑 |
| `loginLimit` | `3` | 登录时最多直接列出的墓碑数量 |

### `graveRecovery`
| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑未能生成时自动返还物品至背包 |
| `removeDroppedItems` | `true` | 返还时清理地面上的同一批掉落物 |
| `removeGraveBackup` | `true` | 返还后删除本次失败放置产生的 `-grave-` 备份 |

### `deathSnapshot`
| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 清理被取消死亡产生的 OpenBlocks 快照 |
| `logCleanup` | `true` | 记录快照清理日志 |
| `logCanceled` | `true` | 记录被取消死亡的伤害源、攻击者与血量 |

### `wirelessredstone`
| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 无线红石附加数据补建总开关 |
| `repairOnLogin` | `true` | 登录时也执行补建 |
| `logLoginRepair` | `false` | 登录路径补建输出 INFO 日志 |
| `logDimensionRepair` | `true` | 跨维度路径补建输出 INFO 日志 |

### `extrautils` / `botania` / `mrtjp` / `worldgen`
| 配置项 | 默认值 | 说明 |
|---|---|---|
| `extrautils.divisionSigil` | `false` | 分割徽章混合服务端适配（GTNH 2.9.0 请设为 `true`） |
| `extrautils.trashCan` | `true` | 垃圾桶读档后重扫库存 |
| `botania.specialFlower` | `true` | 特殊花亮度查询防 NPE |
| `mrtjp.blockUpdate` | `true` | 活跃区块集合防载入未加载区块 |
| `worldgen.enabled` | `true` | 开启级联世界生成诊断日志 |
| `worldgen.logLimit` | `16` | 级联生成诊断日志条数上限（`0` 关闭） |

</details>

---

## 安装与回滚

1. 从 [Releases](https://github.com/Huaxidesu/HorizonFix/releases) 下载 `HorizonFix-1.8.0.jar`（或自行构建）。
2. 放入服务端 `mods/` 目录，**客户端无需安装**。
3. 重启服务端，确认日志出现 `[HorizonFix] 适配 mixin 已启用`。

**回滚**：删除服务端 `mods/` 中的 jar 并重启即可；如需清理数据，可一并删除 `config/HorizonFix.cfg`、`config/HorizonFix-graves.txt` 与 `config/HorizonFix-returns.dat`。

---

## 构建

需要 **JDK 8** 与网络连接（依赖 GTNH ForgeGradle 1.2.13 及 Overmind / GTNH Maven 仓库）：

```bash
export JAVA_HOME=<JDK 8 路径>
./gradlew.in build
```

产物位于 `build/libs/HorizonFix-1.8.0.jar`。

> **注意事项**：
> - `manifest` 中禁止添加 `ModSide` 属性，否则 GTNH 的 JarJar 在 Java 17+ 环境下读取该属性会抛出 `IllegalAccessError`。
> - 仓库配置须保留 `https://gregtech.overminddl1.com/` 以解析 Forge 1.7.10 的 `scala-xml` 依赖。
