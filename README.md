# HorizonFix

GTNH 1.7.10 服务端修复合集。每个修复项都在 `config/HorizonFix.cfg` 里有独立开关，关闭后对应监听器不注册，
运行期开销为零；所有修复都不修改目标模组的字节码，删除 jar 即完全回滚。

| 模块 | 修复内容 | 配置节 |
|---|---|---|
| 墓碑落点 | OpenBlocks 墓碑被放进液体、以及放置彻底失败 | `grave` |
| 无线红石 | WR-CBE Addons 玩家附加数据缺失导致的 NPE 崩服 | `wirelessredstone` |

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

---

## 三、配置

文件：`config/HorizonFix.cfg`（首次启动自动生成，注释为英文以避免 GBK/UTF-8 编码问题）。

键名在正文中以「节名.键名」形式引用，例如 `grave.enabled`；表格中省略节名前缀。

### `grave`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 墓碑修正总开关 |
| `avoidLiquid` | `true` | 原落点在液体内部时重新选点（缺陷 A） |
| `rescueMissing` | `true` | 没有找到落点时补一个（缺陷 B） |
| `searchRadius` | `8` | 搜索半径，按方块计 |
| `requireSupport` | `true` | 第一轮要求下方有实体方块，避免墓碑悬空 |
| `diagnosticLimit` | `16` | 每次启动输出多少条诊断日志，`0` 关闭 |

### `wirelessredstone`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 无线红石补建总开关 |
| `repairOnLogin` | `true` | 登录时补建。WR-CBE 自己也会建，关闭后仅省去一次冗余调用 |
| `logLoginRepair` | `false` | 登录路径补建打 INFO。每次登录都会命中，默认只打 DEBUG |
| `logDimensionRepair` | `true` | 跨维度路径补建打 INFO。这是真正有诊断价值的一类 |

---

## 四、诊断日志

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

无线红石补建（受 `logDimensionRepair` / `logLoginRepair` 控制）：

```text
[HorizonFix] [dim] 玩家 xxx 缺少无线红石附加数据，已在使用前补建（第 3 次）
[HorizonFix] [login] 玩家 xxx 补建无线红石附加数据（第 12 次）
```

---

## 五、关于「重生与放置撞在同一 tick」

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

## 六、安装与回滚

1. 构建产物 `build/libs/HorizonFix-1.0.0.jar`；
2. 放入服务端 `mods/`，建议按现有命名规范改为 `[S][修复]HorizonFix-1.0.0.jar`；
3. **客户端不需要安装**：`acceptableRemoteVersions = "*"` 允许缺少该模组的客户端进入；
4. 重启服务端。

回滚：删除 jar 并重启即可，不残留配置或数据（`config/HorizonFix.cfg` 可一并删除）。

---

## 七、构建

```bash
export JAVA_HOME=<JDK 8>
./gradlew.in build
```

依赖 GTNH 的 ForgeGradle 1.2.13 与 Maven 仓库；OpenBlocks 以 `compileOnly` 引入，
只用到 `openblocks.api.GraveSpawnEvent`，不会打进产物。无线红石部分不依赖任何编译期依赖。

jar 清单不要写 `ModSide` 属性：GTNH 的 JarJar 读取该属性时会访问 `FMLLaunchHandler.side`，
在 Java 17 模块系统下抛 `IllegalAccessError`，服务端在 `setupHome` 阶段直接退出。

`repositories` 必须包含 `https://gregtech.overminddl1.com/`，
否则 Forge 1.7.10 的 dev 依赖 `org.scala-lang:scala-xml_2.11:1.0.2` 解析不到（其余仓库均为 404）。
