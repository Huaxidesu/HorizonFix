# HorizonFix

GTNH 1.7.10 服务端修复合集。**1.5.0 禁止存储总线读取抽屉管理器**：AE2 存储总线贴在 Storage Drawers 抽屉管理器上会周期性遍历全部抽屉并复制物品，抽屉越多 TPS 下降越明显，本版在 AE2 的外部存储入口处拦截；**1.4.0 正式支持 GTNH 2.9.1**（`extrautils.divisionSigil` 默认关闭以防与 2.9.1 上游 Hodgepodge 冲突，保留 2.9.0 开关兼容）；**1.3.0 新增墓碑未生成时的自动返还**：放置彻底失败时不再只提示管理员恢复，而是把物品直接放回玩家背包；**1.3.1 修复墓碑生成后被凋零/爆炸破坏时被误判为「未生成」而重复返还的问题**。事件类修复与第三方 mixin 适配各自在 `config/HorizonFix.cfg` 中有独立开关，关闭后监听器或 mixin 不注册，运行期开销为零；删除 jar 即完全回滚。第三方模组适配以条件加载的 mixin 实现，只注入 ExtraUtilities、Botania、MrTJPCore 自身的类，不改写原版类与 Crucible 补丁过的类；目标模组缺失时不加载。

## 版本与整合包兼容性

| HorizonFix 版本 | 整合包兼容性 | 说明 |
|---|---|---|
| **`1.5.0`（最新）** | **GTNH 2.9.1**（默认）<br>GTNH 2.9.0（可选） | 在 1.4.0 基础上新增存储总线拦截（`ae2` 节），其余修复与兼容性同 1.4.0 |
| **`1.4.0`** | **GTNH 2.9.1**（默认）<br>GTNH 2.9.0（可选） | **正式适配 GTNH 2.9.1**。由于 GTNH 2.9.1 已由上游 Hodgepodge 内置分割徽章修复，本版将 `extrautils.divisionSigil` **默认关闭**以防冲突；若用于 GTNH 2.9.0，手动将该项设为 `true` 即可 |
| **`1.3.1` 及以下**<br>（`1.2.0` ~ `1.3.1`） | **GTNH 2.9.0** | 对应兼容 GTNH 2.9.0。默认启用分割徽章在混合服务端的激活仪式、右键判定与围城信标修复 |

### GTNH 2.9.1 上游修复说明

GTNH 2.9.1 已由上游官方更新修复了以下项目：

- **ExtraUtilities 分割徽章激活（Hodgepodge PR #1058 / #1029）**：
  GTNH 2.9.1 内置的 Hodgepodge 2.7.223+ 已合入 `Fix division sigil activation ritual on Thermos/Crucible`，原生包含 `extrautilities.MixinEventHandlerServer`（仪式判定）、`extrautilities.MixinItemDivisionSigil`（右键附魔台/信标判定）与 `extrautilities.MixinEventHandlerSiege`（围城信标类判定），配置项 `fixExtraUtilitiesDivisionSigilActivation` 默认开启。
- **应对方案**：HorizonFix 1.4.0 新增 `extrautils.divisionSigil` 开关并**默认置为 `false`**，避免与 2.9.1 上游 Hodgepodge 产生重复注入与冲突。若服务器仍在 GTNH 2.9.0，只需在配置中将该项置为 `true`。

---

| 模块 | 修复内容 | 配置节 |
|---|---|---|
| 墓碑落点 | OpenBlocks 墓碑被放进液体、以及放置彻底失败 | `grave` |
| 墓碑提醒 | 墓碑建好后玩家不知道在哪，物品长期留在世界里 | `graveTracker` |
| 墓碑自动返还 | 墓碑确实没有生成时把物品放回玩家背包，无需管理员介入 | `graveRecovery` |
| 无线红石 | WR-CBE Addons 玩家附加数据缺失导致的 NPE 崩服 | `wirelessredstone` |
| 死亡快照 | 死亡被取消后 OpenBlocks 仍会写背包快照，恢复即复制物品 | `deathSnapshot` |
| 级联生成诊断 | 记录区块填充期间触发的相邻区块载入 | `worldgen` |
| **分割徽章激活** | 修复 ExtraUtilities 分割徽章在混合服务端无法激活、激活后右键附魔台或信标无响应 | `extrautils.divisionSigil`<br>（2.9.1 原生已修，默认 `false`） |
| 存储总线与抽屉 | 禁止存储总线读取抽屉管理器，避免轮询全部抽屉造成 TPS 下降 | `ae2` |
| 其他第三方适配 | ExtraUtilities 垃圾桶读档、Botania 亮度查询、MrTJPCore 活跃区块集合 | `extrautils.trashCan`<br>`botania.specialFlower`<br>`mrtjp.blockUpdate` |

mixin 运行期依赖 Mixin 库，GTNH 2.9.0 / 2.9.1 自带的 UniMixins 已提供，无需额外安装。

自动重生、0 血修复属于 Bukkit 插件侧，不在本模组范围内，由
[HorizonFixPlugin](https://github.com/Huaxidesu/HorizonFixPlugin) 提供。两个产物互相独立，可单独安装。

---

## 重点修复：ExtraUtilities 分割徽章激活

> [!NOTE] GTNH 2.9.1 兼容说明
> 在 GTNH 2.9.1 中，上游 Hodgepodge 2.7.223+（PR #1058）已原生内置本修复（`fixExtraUtilitiesDivisionSigilActivation` 默认 `true`）。
> 因此 HorizonFix 1.4.0 起，`extrautils.divisionSigil` **默认设为 `false`** 以防冲突。
> 若仍运行于 GTNH 2.9.0 环境，请在 `config/HorizonFix.cfg` 中将 `divisionSigil` 手动改为 `true`。

### 现象

在 Crucible / Thermos / Cauldron 这类混合服务端上，ExtraUtilities 的分割徽章（`Division Sigil`，游戏内亦称除法印记）激活仪式无法完成：按配方摆好祭坛后仪式不触发，徽章始终处于未激活状态；已激活的徽章右键附魔台或信标也没有响应。

### 原因

ExtraUtilities 用方块对象的引用相等比较来判定祭坛：

```java
world.getBlock(x, y, z) == Blocks.enchanting_table
```

混合服务端与整合包会替换或包装原版方块，此时取到的是 `BlockEnchantmentTable` 的子类实例，与 `Blocks.enchanting_table` 不是同一对象，比较恒为假，激活仪式与徽章右键判定随之全部失效。围城事件对信标 TileEntity 使用 `Class.equals` 严格比较，同样会因子类化而失效。

### 修复

- `MixinExtraUtilsActivationRitual`：把仪式判定取到的附魔台归一到原版方块单例。
- `MixinExtraUtilsDivisionSigil`：把徽章右键取到的附魔台与信标归一到原版方块单例。
- `MixinExtraUtilsSiege`：把信标 TileEntity 的类相等判定改为可赋值判定，允许子类。

三处注入只作用于 ExtraUtilities 自身的类，不改写原版类与混合服务端补丁过的类；目标模组缺失时不加载对应 mixin。

### 验收

1. 在混合服务端按 ExtraUtilities 的流程执行分割徽章激活仪式，徽章应进入激活状态。
2. 已激活的徽章右键附魔台与信标应正常响应。
3. 启动日志出现 `[HorizonFix] 适配 mixin 已启用 6 项`，且无 `InvalidInjectionException`。

---

## 一、墓碑落点（`grave`）

OpenBlocks 有两个独立缺陷：

| 编号 | 现象 | 原因 |
|---|---|---|
| A | 墓碑生成成功但埋在岩浆里，玩家看不见、挖不到 | 放置检查器把液体当成可替换方块，死亡点在岩浆里时第一个候选坐标就被接受 |
| B | 日志出现 `No location for grave found`，背包物品原地掉落并在岩浆中烧毁 | 放置判定依赖 `World.blockExists`，该方法只查询「当前是否已载入内存」，判定失败时全部候选被拒绝 |

### 为什么是补丁模组，而不是修改 OpenBlocks

OpenBlocks 的放置流程本身留了扩展点：

```java
Coord location = findLocation(world, player);                       // 内部检查器选点
GraveSpawnEvent evt = location == null
        ? new GraveSpawnEvent(player, loot, ...)                    // hasLocation = false
        : new GraveSpawnEvent(player, location.x, location.y, location.z, ...);
if (MinecraftForge.EVENT_BUS.post(evt)) return false;               // 事件可取消
if (!evt.hasLocation()) return false;                               // ← 失败分支
tryPlaceGrave(world, evt.getX(), evt.getY(), evt.getZ(), ...);       // 用事件里的坐标写入
```

`GraveSpawnEvent` 是公开 API，并且提供了 `setPosition(int, int, int)`。因此在事件回调里改写坐标，
既能修 A（原坐标在液体里就换一个），也能修 B（原坐标为 `null` 就补一个），
而且不需要改动 OpenBlocks 的任何字节码，也就不会在整合包升级时被覆盖。

本模块只在两种情况介入，其余情况直接返回：

1. `hasLocation() == true` 且落点方块是液体（`grave.avoidLiquid`）；
2. `hasLocation() == false`（`grave.rescueMissing`）。

### 选点逻辑

1. 先按死亡点所在区块坐标取区块，必要时从磁盘同步载入；不依赖 `World.blockExists`；
2. 第一轮：死亡点 ±`grave.searchRadius` 范围内按曼哈顿距离由近到远，要求目标位置是空气或可替换方块、
   且不是液体、且下方有实体方块（`grave.requireSupport`）；
3. 第二轮：放宽「下方有实体方块」这一条，只要求不是液体；
4. 后备处理：沿死亡点所在列向上、向下扫描，再扩到相邻四列，取第一个可用位置。

因为第 4 步几乎总能给出坐标，`trySpawnGrave` 不会再走到 `spawnEntityInWorld` 的失败分支，
物品不会在岩浆里被销毁。

---

## 二、无线红石（`wirelessredstone`）

崩溃点：`RedstoneEtherServerAddons.deactivateRemote(World, EntityPlayer)` 通过
`playerInfos.get(player.getCommandSenderName())` 取附加数据，取不到时直接访问 `info.remote`，抛出 NPE。
`onDimensionChange()` 的顺序恰好是「先 deactivate、后 put」，因此玩家只要在切维度那一刻不在表里，必然命中。

走传送门时异常被 Forge 在该层捕获，只强制断开当事人的连接；走 ServerUtilities 的 `/home`、`/tpa`、`/spawn` 时
异常冒到世界 tick，整个服务器崩溃。

本模块在 `PlayerLoggedInEvent` / `PlayerChangedDimensionEvent` 上以 `HIGHEST` 优先级先于 WR-CBE 的
`NORMAL` 监听器执行：发现条目缺失就调用它自己的 `onLogin(player)` 补建，使后续读取拿到合法引用。

全部通过反射访问 WR-CBE 内部结构，任何一步失败都只让补丁静默失效，不影响服务器启动。

### 一死两碑

同一 tick 内触发两次死亡时，OpenBlocks 会排队两次放置：第一座墓碑拿到全部掉落，第二座只剩死亡时
额外追加的物品（例如大头插件补的玩家头颅），于是出现「一死两碑」且头颅多一颗。

`GraveSpawnEvent` 可取消，本模组在 `HIGHEST` 记录每个玩家的上次生成 tick，同 tick 再来一次就取消，
OpenBlocks 会把该次掉落丢回地面，不再产生第二座墓碑。开关为 `grave.singlePerTick`。

---

## 三、墓碑位置提醒（`graveTracker`）

OpenBlocks 只把死亡原因和天数写进墓碑文字，**不给玩家任何坐标**。玩家重生点往往离死亡点很远，
于是出现「墓碑一直在、玩家以为东西丢了」——实际案例里 704 件物品在一座没人取走的墓碑里躺了几个小时。

本模块在墓碑放置后记录位置，并在三个时机告知玩家：

1. **放置时**：聊天栏提示坐标与维度；
2. **登录时**：若有未取走的墓碑，再提醒一次（超过 `loginLimit` 只给数量）；
3. **`/graves`**：随时列出全部未取走的墓碑，含「多久以前」。

```
[墓碑] 你的物品放在 主世界 (5232, 87, 11842)，取走前不会消失
[墓碑] 未取走的墓碑：主世界 (5232, 87, 11842)（3 小时前）
```

**「是否已取走」只在区块已载入时判定**：未载入的区块不做任何载入动作，按未取走列出——
玩家到现场自然能确认，代价是不会为了检查而载入远处区块。

记录持久化在 `config/HorizonFix-graves.txt`，每行 `uuid|维度|x|y|z|时间戳`，全局上限 256 条、
单玩家 16 条。该文件可随时删除，删除后只是丢失历史记录。

---

## 四、墓碑未生成时自动返还（`graveRecovery`）

`GraveSpawnEvent` 只是「准备放置」。OpenBlocks 在事件之后才写方块，`tryPlaceGrave` 失败时执行的是：

```java
for (EntityItem drop : loot) world.spawnEntityInWorld(drop);
```

即把本该进墓碑的物品丢回死亡位置。死亡点位于岩浆内部时这批物品会直接烧毁，其余情况也需要玩家
回到原地逐个捡起。1.2.x 及以前的做法是放置后 2 tick 校验一次方块，不是墓碑就提示玩家联系管理员
用 `/ob_inventory restore` 恢复，仍需人工介入。

本模块改为自动完成：

1. 在 `GraveSpawnEvent`（`LOWEST`，晚于落点修正）记下本次墓碑应装入的物品，并保留对应的
   `EntityItem` 引用；
2. 2 tick 后检查该坐标：方块是 `BlockGrave` 且 TileEntity 是 `TileEntityGrave` 才算成功；
3. 判定失败时依次执行：
   - 移除 OpenBlocks 丢到地面的同一批掉落物（`graveRecovery.removeDroppedItems`）；
   - 把物品放回玩家背包，背包已满的部分掉在脚下；
   - 删除本次失败放置留下的 `-grave-` 备份（`graveRecovery.removeGraveBackup`）；
   - 移除 `HorizonFix-graves.txt` 里对应的位置记录，`/graves` 不再列出。

玩家不在线、或仍停留在死亡界面时，物品先写入 `config/HorizonFix-returns.dat`，登录或重生后自动发放，
不会因为掉线而丢失。

### 与 OpenBlocks 备份的关系

OpenBlocks 每次死亡都会往 `world/data` 写两份文件：

| 文件 | 内容 | 本模块的处理 |
|---|---|---|
| `inventory-<玩家>-<时间>-death-N.dat` | 死亡瞬间的完整背包，`/ob_inventory restore` 的常用来源 | **保留**。正常放置成功时它同样存在，属既有兜底手段 |
| `inventory-<玩家>-<时间>-grave-N.dat` | 墓碑实际装入的物品，`Placed=false` 表示放置失败 | 自动返还后**删除**，否则再执行一次 `/ob_inventory restore` 就会复制一份 |

只删除 `Placed=false`、UUID 与写入时间都对得上的那一份，成功放置的备份不受影响。

### 判定边界

- 事件被取消（同一 tick 的第二次死亡）时不介入：那次掉落由 OpenBlocks 丢回地面，属预期行为。
- **墓碑生成后被爆炸或凋零破坏时不做返还**：OpenBlocks 破坏墓碑会把内容物掉到原地
  （`OpenBlock.breakBlock` → `getTileBreakDrops`），此时再返还就是复制物品。
  方块不在时改为读取 OpenBlocks 自己写的备份：`Placed=true` 表示放置成功过，只有 `Placed=false`
  才是放置失败。凋零贴脸死亡时墓碑可能在 2 tick 内被炸掉，这条判定专门覆盖该场景。
- 备份关闭（`backupGraves=false`）时没有 `Placed` 标记可用：方块不在且事件带坐标时无法区分
  「放置失败」与「生成后被破坏」，此时不返还，保持 1.2.x 的行为。
- 世界未载入导致无法判定时先等待，最多 200 tick；仍无法判定则按「已生成」处理并输出警告，
  不做返还——宁可让玩家自己去取，也不制造复制漏洞。
- 自动返还关闭（`graveRecovery.enabled=false`）时恢复 1.2.x 的行为：只提示，不改动物品。

### 与自动重生插件（HorizonFixPlugin）的协作

HorizonFixPlugin 默认在死亡后 5 tick 调用 `Player.spigot().respawn()`，晚于本模块 2 tick 的校验：
校验时玩家仍处于死亡状态，物品先写入 `config/HorizonFix-returns.dat`，在 `PlayerRespawnEvent` 上发放
（server2 实测）。把 `respawn.delay-ticks` 调到 1 时，重生先于校验发生，物品直接进入背包。

墓碑放置本身与死亡同 tick，早于任何重生任务，不受自动重生影响；正常死亡仍会生成墓碑，
此时不会触发返还。

---

## 五、被取消死亡的快照（`deathSnapshot`）

OpenBlocks 的 `PlayerInventoryStore.onPlayerDeath` 在 `HIGH` 优先级无条件落盘，不检查 `isCanceled()`；
Witchery 的吸血鬼免疫在 `NORMAL` 才取消死亡，顺序在后。于是每次「打不死」都会多出一份
`-death-N.dat`，而真正掉物品的 `PlayerDropsEvent` 并未触发——既没有死亡提示，也不会立墓碑。

这些快照本身无害，但 `/ob_inventory restore` 不校验玩家当前是否已有这些物品，
恢复任何一份都等于凭空复制一整包东西。

本模块在 `HIGHEST`（早于 OpenBlocks）记录快照目录的文件列表，在 `LOWEST`（晚于所有取消动作，
`receiveCanceled = true`）检查事件是否被取消；被取消则删除本次事件期间新增的该玩家 death 快照。
只删本次新增的文件，不碰历史快照。

---

## 六、存储总线与抽屉管理器（`ae2`）

### 现象

把 AE2 的存储总线贴在 Storage Drawers 的抽屉管理器（Drawer Controller）上，服务器 TPS 明显下降；
抽屉越多、总线越多，下降越明显。

### 原因

Storage Drawers 向 AE2 注册的外部存储处理器把整个抽屉组包装成 `DrawerMEInventory`
（`DrawerExternalStorageHandler.canHandle` 判定 `channel == StorageChannel.ITEMS && te instanceof IDrawerGroup`）。
存储总线的轮询链路是：

```text
PartStorageBus（每 5–60 tick）
  → MEMonitorIInventory.onTick()
    → IMEAdaptor.iterator()         每次新建列表
      → DrawerMEInventory.getAvailableItems()
        → 遍历管理器下全部抽屉
          → BaseDrawerData.getStoredItemCopy()   每个非空抽屉做一次完整 ItemStack.copy()（含 NBT）
```

因此单次轮询的开销与抽屉数量成正比，且每次都重新复制一遍物品数据。这是结构性问题，
上游已知多年（AE2 issue #2831、Storage Drawers issue #442），作者明确表示不会修改。

### 为什么不能靠配置解决

- **AE2 的 `TickRates`** 只能把轮询间隔从 5–60 tick 调大，单次开销不变，TPS 仍随抽屉数量恶化；
- **关闭 Storage Drawers 的 `enableAE2`** 反而更慢：抽屉管理器实现了 `IInventory`，
  AE2 会退化到兜底的 `ExternalIInv`，逐槽位比对，且丢失 void / smart 语义。

### 修复

`MixinAe2ExternalStorageRegistry` 注入 `ExternalStorageRegistry.getHandler`，
在入口处按类名识别抽屉方块并返回一个恒空的库存：

- 恒空而非 `null`：返回 `null` 会让存储总线认为邻接方块不可用，每 tick 重建缓存并重新调度，开销更大；
  返回合法 handler 后总线没有监视器，会自行进入休眠。
- 按类名判定，不加载 Storage Drawers 的类：`TileEntityController`、`TileEntitySlave`（下属从站，
  读写全部代理到管理器，开销相同）、`TileEntityDrawers`（单个抽屉，默认不拦）。
- 拦截生效时向 8 格内的玩家发送一次聊天提示，并在日志记录坐标，避免被误判为总线故障。
- 启动时做一次功能性自检：构造一个抽屉管理器实例，走一遍 AE2 的取处理器入口，
  确认返回的是拦截器。生效时日志为 `存储总线拦截已生效`，注入失败会明确告警，不会静默失效。

替代做法是改用 ME 接口（ME Interface）接入 ME 网络，接口只搬运指定物品，不做全量扫描。

---

## 七、配置

文件：`config/HorizonFix.cfg`（首次启动自动生成，注释为英文以避免 GBK/UTF-8 编码问题）。

键名在正文中以「节名.键名」形式引用，例如 `grave.enabled`；表格中省略节名前缀。

### `grave`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑修正总开关 |
| `avoidLiquid` | `true` | 原落点在液体内部时重新选点（缺陷 A） |
| `rescueMissing` | `true` | 没有找到落点时补一个（缺陷 B） |
| `singlePerTick` | `true` | 同一玩家同一 tick 只生成一座墓碑，避免「一死两碑」 |
| `searchRadius` | `8` | 搜索半径，按方块计 |
| `requireSupport` | `true` | 第一轮要求下方有实体方块，避免墓碑悬空 |
| `diagnosticLimit` | `16` | 每次启动输出多少条诊断日志，`0` 关闭 |

### `graveTracker`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑位置记录与提醒总开关，同时控制 `/graves` 是否注册 |
| `notifyOnPlace` | `true` | 放置时把坐标发到聊天栏 |
| `notifyOnLogin` | `true` | 登录时提醒未取走的墓碑 |
| `loginLimit` | `3` | 登录时最多直接列出几座，超出只给数量 |

### `graveRecovery`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑未能生成时自动把物品放回背包。关闭后只提示，不改动物品 |
| `removeDroppedItems` | `true` | 移除 OpenBlocks 在放置失败时丢到地面的同一批物品，避免与返还的物品重复 |
| `removeGraveBackup` | `true` | 删除本次失败放置留下的 `-grave-` 备份，避免 `/ob_inventory restore` 再复制一份 |

### `deathSnapshot`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 被取消死亡的快照清理总开关 |
| `logCleanup` | `true` | 每次清理输出一条日志 |
| `logCanceled` | `true` | 死亡被取消时输出伤害源、攻击者与当前血量 |

### `wirelessredstone`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 无线红石补建总开关 |
| `repairOnLogin` | `true` | 登录时补建。WR-CBE 自己也会建，关闭后仅省去一次冗余调用 |
| `logLoginRepair` | `false` | 登录路径补建打 INFO。每次登录都会命中，默认只打 DEBUG |
| `logDimensionRepair` | `true` | 跨维度路径补建打 INFO。这是真正有诊断价值的一类 |

### `worldgen`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 记录区块填充期间触发的相邻区块载入 |
| `logLimit` | `16` | 单次启动的日志条数上限，`0` 关闭 |

### `extrautils`

| 键 | 默认 | 说明 |
|---|---|---|
| `divisionSigil` | `false` | 修复混合服务端上分割徽章激活仪式、右键附魔台与信标、围城信标判定。**GTNH 2.9.1+ 已由 Hodgepodge 原生合入，因此默认关闭**；GTNH 2.9.0 请设为 `true` 开启 |
| `trashCan` | `true` | 读档后强制垃圾桶在下一次 tick 重扫库存，避免残留物品未进入销毁队列 |

### `botania`

| 键 | 默认 | 说明 |
|---|---|---|
| `specialFlower` | `true` | 特殊花亮度查询在目标位置没有 TileEntity 时返回缺省亮度，防御 NPE |

### `mrtjp`

| 键 | 默认 | 说明 |
|---|---|---|
| `blockUpdate` | `true` | MrTJPCore 活跃区块集合只加入已加载区块，避免后续计算带入未加载区块 |

### `ae2`

| 键 | 默认 | 说明 |
|---|---|---|
| `denyDrawerController` | `true` | 禁止存储总线读取抽屉管理器及其下属从站 |
| `denyDrawers` | `false` | 同时禁止读取单个抽屉方块。单抽屉开销小，默认不拦 |
| `notifyPlayers` | `true` | 拦截生效时向附近玩家发送聊天提示 |

---

## 八、诊断日志

墓碑失败时（受 `grave.diagnosticLimit` 限制）：

```text
[墓碑诊断] 候选统计 r=5 total=1331 blockExists=1331 canMineBlock=1331 air=137 replaceable=137 liquid=0
[墓碑诊断] 未找到任何落点 @(-3435,15,5185) dim=-1 blockExists=false chunkExists=false block=...:0 liquid=false replaceable=false air=false
[墓碑时序] delta=1 tick deathDim=-1 playerDim=-1 @(14,29,-36) blockExists=true chunkExists=true
```

- `blockExists` 远小于 `total` → 失败在「区块没载入」；
- `blockExists == total` 但 `air + replaceable == 0` → 失败在「方块不可放置」；
- `canMineBlock` 小于 `total` → 失败在权限或保护类判定；
- `delta=1` → 放置回调与死亡同 tick（世界时间在实体 tick 之后、END 事件之前自增一次），
  自动重生任务不可能先于它执行；`delta>=2` 才表示放置被推到了下一 tick 或更晚。

死亡被取消时（受 `logCanceled` 控制）：

```text
[HorizonFix] 死亡被取消 #3 玩家=xxx src=inWall attacker=- direct=- health=0.0 pos=(-52857,15,-52468) dim=7 清理=1
```

`src` 是 `DamageSource.damageType`，`attacker` 与 `direct` 分别是伤害来源实体与直接来源实体。
1.7.10 的 `DamageSource` 不携带伤害数值，因此日志里没有 amount。

无线红石补建（受 `logDimensionRepair` / `logLoginRepair` 控制）：

```text
[HorizonFix] [dim] 玩家 xxx 缺少无线红石附加数据，已在使用前补建（第 3 次）
[HorizonFix] [login] 玩家 xxx 补建无线红石附加数据（第 12 次）
```

级联世界生成（受 `worldgen.logLimit` 控制）：

```text
[HorizonFix] 级联世界生成 dim=0 来源区块=(12,-5) 触发载入=(13,-5)
```

`来源区块` 是正在执行填充的区块，`触发载入` 是在此期间被载入的另一区块。该日志只用于定位责任模组，不改变生成行为。

墓碑失败后的自动返还：

```text
[HorizonFix] xxx 的墓碑未能生成，已把 37 件物品放回背包（0 件因背包已满掉在脚下）
[HorizonFix] xxx 的墓碑未能生成，玩家不在线，37 件物品已暂存，登录后自动放回
[HorizonFix] 已删除墓碑备份 inventory-xxx-2026-11-11_17.20.03-grave-0.dat
[HorizonFix] 无法判定 xxx 的墓碑是否生成，按已生成处理，不改动物品
```

最后一条出现在世界未载入且等待 200 tick 仍无法判定的情况，此时不做返还。

存储总线拦截：启动时输出一次自检结果，之后每个被拦截的方块输出一次坐标。

```text
[HorizonFix] 存储总线拦截已生效（抽屉管理器=禁止，单个抽屉=允许）
[HorizonFix] 已禁止存储总线读取抽屉管理器 @dim=0 (128, 64, -30)
```

自检失败时输出 `存储总线拦截未生效（判定=…，入口=…）`，说明 mixin 没有注入，此时存储总线仍会读取抽屉。
坐标记录上限 32 条，超过后只发送玩家提示，避免日志被大量总线刷屏。

---

## 九、关于「重生与放置撞在同一 tick」

交接资料提出：自动重生任务与墓碑放置排在同一 tick，顺序不稳，重生先执行时区块失去加载来源。
按 1.7.10 的实际调用链，**这条不成立**：

```java
// MinecraftServer.func_71190_q()（FML 补丁）
FMLCommonHandler.instance().onPreWorldTick(worldserver);   // WorldTickEvent START
worldserver.tick();                                        // ← 玩家死亡，GraveCallable 入队
FMLCommonHandler.instance().onPostWorldTick(worldserver);  // WorldTickEvent END → 立即执行回调
worldserver.func_73039_n().func_72788_a();                 // 区块关注更新在这之后
```

`DelayedActionTickHandler` 在 `WorldTickEvent` 的 END 阶段执行（已核对现场 `OpenModsLibs-0.10.15.jar`），
而 Bukkit 的 `scheduleSyncDelayedTask(…, 1)` 最早在下一个服务端 tick 的调度心跳执行。
因此**墓碑放置永远发生在死亡当 tick 内**，早于任何重生任务，不存在时序冲突。

`AutoRespawnPlus` 的 `ticks < 1 ? 1 : ticks` 确实是代码事实（配置写 0 实际是 1 tick），
但它只说明「重生不可能是 0 tick」，推不出「重生会先于放置执行」。

---

## 十、安装与回滚

1. 构建产物 `build/libs/HorizonFix-1.5.0.jar`；
2. 放入服务端 `mods/`，建议按现有命名规范改为 `[S][修复]HorizonFix-1.5.0.jar`；
3. **客户端不需要安装**：`acceptableRemoteVersions = "*"` 允许缺少该模组的客户端进入，第三方适配均作用于服务端代码路径；
4. 重启服务端。

运行环境需具备 Mixin 库，GTNH 2.9.0 / 2.9.1 自带的 UniMixins 已满足该条件。
若为 GTNH 2.9.0 环境，需在 `config/HorizonFix.cfg` 中将 `extrautils.divisionSigil` 手动设为 `true`。GTNH 2.9.1 保持默认 `false` 即可。

回滚：删除 jar 并重启即可。事件类修复不写入存档，第三方 mixin 随 jar 一起移除；`config/HorizonFix.cfg`、
`config/HorizonFix-graves.txt`、`config/HorizonFix-returns.dat` 可一并删除。

---

## 十一、构建

```bash
export JAVA_HOME=<JDK 8>
./gradlew.in build
```

依赖 GTNH 的 ForgeGradle 1.2.13 与 Maven 仓库；OpenBlocks、Mixin、SpongeMixins 均以 `compileOnly` 引入，
不打进产物。OpenBlocks 只用到 `openblocks.api.GraveSpawnEvent`；mixin 的目标类以字符串指定，
因此不需要 ExtraUtilities、Botania、MrTJPCore 的编译期 jar。jar 清单包含 `TweakClass` 与 `MixinConfigs`，
由运行期的 Mixin 库读取。

jar 清单不要写 `ModSide` 属性：GTNH 的 JarJar 读取该属性时会访问 `FMLLaunchHandler.side`，
在 Java 17 模块系统下抛 `IllegalAccessError`，服务端在 `setupHome` 阶段直接退出。

`repositories` 必须包含 `https://gregtech.overminddl1.com/`，
否则 Forge 1.7.10 的 dev 依赖 `org.scala-lang:scala-xml_2.11:1.0.2` 解析不到（其余仓库均为 404）。
