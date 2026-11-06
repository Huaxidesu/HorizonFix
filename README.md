# HorizonFix

GTNH 1.7.10 服务端修复合集。事件类修复各自在 `config/HorizonFix.cfg` 中有独立开关，关闭后监听器不注册，
运行期开销为零；删除 jar 即完全回滚。第三方模组适配以条件加载的 mixin 实现，只注入 ExtraUtilities、
Botania、MrTJPCore 自身的类，不改写原版类与 Crucible 补丁过的类；目标模组缺失时对应 mixin 不加载。

| 模块 | 修复内容 | 配置节 |
|---|---|---|
| 墓碑落点 | OpenBlocks 墓碑被放进液体、以及放置彻底失败 | `grave` |
| 墓碑提醒 | 墓碑建好后玩家不知道在哪，物品长期留在世界里 | `graveTracker` |
| 无线红石 | WR-CBE Addons 玩家附加数据缺失导致的 NPE 崩服 | `wirelessredstone` |
| 死亡快照 | 死亡被取消后 OpenBlocks 仍会写背包快照，恢复即复制物品 | `deathSnapshot` |
| 级联生成诊断 | 记录区块填充期间触发的相邻区块载入 | `worldgen` |
| 第三方适配 | ExtraUtilities 四处、Botania 一处、MrTJPCore 一处的兼容性与防崩修复 | 按目标模组存在与否加载 |

mixin 运行期依赖 Mixin 库，GTNH 2.9.0 自带的 UniMixins 已提供，无需额外安装。

自动重生、0 血修复属于 Bukkit 插件侧，不在本模组范围内，由
[HorizonFixPlugin](https://github.com/Huaxidesu/HorizonFixPlugin) 提供。两个产物互相独立，可单独安装。

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

## 四、被取消死亡的快照（`deathSnapshot`）

OpenBlocks 的 `PlayerInventoryStore.onPlayerDeath` 在 `HIGH` 优先级无条件落盘，不检查 `isCanceled()`；
Witchery 的吸血鬼免疫在 `NORMAL` 才取消死亡，顺序在后。于是每次「打不死」都会多出一份
`-death-N.dat`，而真正掉物品的 `PlayerDropsEvent` 并未触发——既没有死亡提示，也不会立墓碑。

这些快照本身无害，但 `/ob_inventory restore` 不校验玩家当前是否已有这些物品，
恢复任何一份都等于凭空复制一整包东西。

本模块在 `HIGHEST`（早于 OpenBlocks）记录快照目录的文件列表，在 `LOWEST`（晚于所有取消动作，
`receiveCanceled = true`）检查事件是否被取消；被取消则删除本次事件期间新增的该玩家 death 快照。
只删本次新增的文件，不碰历史快照。

---

## 五、配置

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

---

## 六、诊断日志

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

---

## 七、关于「重生与放置撞在同一 tick」

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

## 八、安装与回滚

1. 构建产物 `build/libs/HorizonFix-1.2.0.jar`；
2. 放入服务端 `mods/`，建议按现有命名规范改为 `[S][修复]HorizonFix-1.2.0.jar`；
3. **客户端不需要安装**：`acceptableRemoteVersions = "*"` 允许缺少该模组的客户端进入，六处第三方适配均作用于服务端代码路径；
4. 重启服务端。

运行环境需具备 Mixin 库，GTNH 2.9.0 自带的 UniMixins 已满足该条件。

回滚：删除 jar 并重启即可。事件类修复不写入存档，第三方 mixin 随 jar 一起移除；`config/HorizonFix.cfg` 可一并删除。

---

## 九、构建

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
