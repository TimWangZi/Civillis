# Civillis 多玩家文明系统 — 技术方案

---

## 一、设计原则

**不修改全局 L1→L2 评分管道。** 在现有文明评分之上叠加一层归属映射：

```
VoxelChunk  →  CScore（方块评分，全局不变）  →  是否文明
            →  FactionId（归属层，结构驱动）  →  谁的文明  →  颜色 / 权限
```

- 方块评分决定「这里是否文明」
- 结构归属决定「这片文明属于谁」
- 两层各自独立：可能出现无主 HIGH 区域（文明评分高但无 Faction 认领）

---

## 二、Faction 数据模型

### 2.1 Faction

```java
// civil/faction/Faction.java

public record Faction(
    UUID id,              // 唯一标识
    String name,          // 显示名
    UUID owner,           // 创建者（最高权限）
    Set<UUID> members,    // 成员（不含 owner）
    int color,            // ARGB，创建时从 24 色调色板自动分配
    long createdAt        // 创建时间戳
) {}
```

### 2.2 FactionManager

```java
// civil/faction/FactionManager.java

public final class FactionManager {
    Map<UUID, Faction> factions;                    // factionId → Faction
    Map<UUID, UUID> playerFactionMap;               // playerId → factionId
    Map<UUID, Long> invitations;                    // playerId → factionId（60 秒过期）

    void initialize(CivilStorage storage);
    void shutdown();
    void flushDirty();

    // === 生命周期 ===
    Faction createFaction(UUID owner, String name);
    UUID getOrCreateFactionForPlayer(UUID playerId, String name);
    boolean disbandFaction(UUID factionId);

    // === 成员管理 ===
    boolean join(UUID playerId, UUID factionId);
    boolean leave(UUID playerId);
    boolean invite(UUID inviterId, UUID targetId);
    boolean kick(UUID ownerId, UUID targetId);

    // === 权限 ===
    boolean transferOwnership(UUID factionId, UUID newOwner);
    boolean setColor(UUID playerId, int color);
    boolean setOpenRegistration(UUID playerId, boolean open);

    // === 查询 ===
    Faction getFaction(UUID id);
    Faction getPlayerFaction(UUID playerId);
    boolean isMember(UUID playerId, UUID factionId);

    // === ★ 空间归属查询 ===
    Faction getFactionAt(ServerLevel world, BlockPos pos);
}
```

---

## 三、玩家归属流程

```
玩家放置并激活文明结构（讲台 / 灵魂营火 / 绿宝石块）
   │
   ▼
getOrCreateFactionForPlayer(playerId, name)
   ├─ playerFactionMap 有记录  →  返回已有 Faction
   └─ 无                      →  createFaction()  →  返回新 Faction
   │
   ▼
结构 Entry 绑定 factionId

其他玩家加入：
   ├─ invite(playerId)       →  被邀请者 join(factionId)
   ├─ openRegistration=true  →  自主 join(factionId)
   └─ 加入后 = 该 Faction 下所有结构自动共享权限（生成保护、传送锚点、增益效果）

成员退出：
   ├─ 普通成员 leave()   →  从 members 移除，playerFactionMap 删映射。立即失去所有权限
   └─ Owner leave()
       ├─ 有成员  →  自动转让 owner 给第一个成员，原 owner 降为普通成员后退出
       └─ 无成员  →  disband()
                      ├─ 该 Faction 所有结构的 factionId 清空为 null（变无主）
                      ├─ 地图恢复默认金色
                      ├─ 生成保护回退到全局 CScore 判定
                      └─ Faction 从 factions.nbt 删除
```

---

## 四、结构所有权绑定

三种结构 Entry 均新增 `factionId: UUID`：

| 结构 | 绑定时机 | 代码位置 |
|------|---------|---------|
| TownCenter | 讲台 + 成书 + 绿宝石激活 | `TownCenterActivationHandler` |
| FarmShrine | 骨头右击点燃的灵魂营火 | `FarmShrineActivationHandler` |
| UndyingAnchor | 不死图腾右击绿宝石块 | `UndyingAnchorActivationHandler` |

持久化：`NbtStorage` 中三种结构的 NBT 序列化各增加 `factionId` 字段。旧存档缺省值视为 `null`（无主兼容）。

---

## 五、空间归属判定

```java
// FactionManager.getFactionAt(world, pos)

遍历所有已激活 TownCenter
  → 检查 VC 是否在 TownCenterAabb 内
      （范围由 TownCenterLevelRegistry 的等级半径决定）
  → 收集 (distance², factionId)
取最近  →  factions.get(factionId)
无匹配 →  null（无主）
```

重叠优先级：距离最近  →  等级最高  →  先建优先。

`TownCenterTracker.entriesCoveringVc()` 提供的 VC 空间索引将查询从 O(N) 降为 O(候选数)。

---

## 六、Wall 边界渲染

### 6.1 颜色体系（4 种墙）

| 墙类型 | RGB | 渲染器 |
|--------|-----|--------|
| 自己的文明 | 琥珀金 (0.90, 0.78, 0.50) | `AuraWallRenderer`（不动） |
| 他人文明 | 深橙 (0.92, 0.45, 0.20) | **新** `AuraPlayerWallRenderer` |
| 神社 | 紫水晶 (0.68, 0.40, 0.88) | `AuraWallRenderer`（不动） |
| 警戒 | 警示橙 (1.0, 0.50, 0.30) | `AuraWallRenderer`（不动） |

### 6.2 服务端

**SonarScan**：新增 `scannerFactionId` + `vcFactionCache`（`Map<VoxelChunkKey, UUID>`）。BFS 过程中每遇新 HIGH VC 即缓存其 factionId。对外暴露 `factionOf(VoxelChunkKey): UUID?`。

**SonarScanManager.sendBoundaryPacket()**：面收集时按归属拆分为两个列表：

```java
List<BoundaryFaceData> ownFaces = new ArrayList<>();
List<BoundaryFaceData> foreignFaces = new ArrayList<>();

for (BoundaryFace face : civFaces) {
    if (overlapsShrineOrZone(face)) continue;
    UUID highFaction = scan.factionOf(face.highSide());
    boolean isOwn = (scannerFactionId == null && highFaction == null)
                 || scannerFactionId.equals(highFaction);

    BoundaryFaceData data = BoundaryFaceData.fromBoundaryFace(face, wallMinY, wallMaxY);
    (isOwn ? ownFaces : foreignFaces).add(data);
}
```

**SonarBoundaryPayload**：新增 `List<BoundaryFaceData> foreignFaces`，编解码与 `faces` 一致。

### 6.3 客户端

**`AuraWallRenderer`：零改动。**

**新文件 `AuraPlayerWallRenderer.java`**（`civil.aura` 包）：

- 独立自持全部状态：phaseAlpha / visibleAfterNano / steadyEndNano / lastFrameNano
- 独立维护 face 列表：timedFaces / faceArrivalMap / FaceId / TimedFace
- 独立 GPU 缓冲：ALLOCATOR / vertexBuffer
- `updateBoundaries(SonarBoundaryPayload)` — 处理 payload.foreignFaces
- `onRender(Vec3 cameraPos)` — 每帧渲染
- `renderWalls(...)` — 与 `renderCivilizationWalls` 逻辑一致：alpha 分 10 桶 → 正反双向四边形 → `drawBucketedWalls(..., PLAYER_R/G/B, ..., kind=3)`

**平台注册**（`CivilModClientFabric` / `CivilModClientNeoForge`）：

- 网络接收处追加：`AuraPlayerWallRenderer.updateBoundaries(payload);`
- 渲染事件处追加：`AuraPlayerWallRenderer.onRender(cameraPos);`

---

## 七、地图着色

**改造链路**：

```
CivilMapTintPalette.evaluateTintForChunk()
  → 原逻辑判定 band (HIGH / MONSTER / ZONE / UNKNOWN)
  → band == HIGH 时额外调用 FactionManager.getFactionAt()
  → 返回 (band, factionColor)

CivilMapTintUpdateSession
  → 透传 factionColor 到 bake 环节

CivilMapColorBake.blendPackedMapByte()
  → band == HIGH 且 factionColor != 0  →  用 faction 色替代默认金色
  → factionColor == 0（无主）         →  保持原金色
  → band 非 HIGH                      →  保持原色（神社紫 / 警戒橙 / 无色）
```

| 文件 | 改动 |
|------|------|
| `CivilMapTintPalette.ChunkTintEval` | +`factionColor: int` 字段 |
| `CivilMapTintPalette.evaluateTintForChunk` | HIGH 分支调用 `FactionManager.getFactionAt()` |
| `CivilMapColorBake` | 新增 `blendFillWithColor(mapByte, factionColor, alpha)` 方法 |
| `CivilMapColorBake.blendPackedMapByte` | HIGH + factionColor≠0 时调用新方法 |
| `CivilMapTintUpdateSession.Context` | 携带 factionColor 到 bake 调用 |

---

## 八、生成控制

`SpawnPolicy.decide()` 在 CScore 判定之前（原第 2.5 步）插入 faction 判定：

```java
// ★ 新步骤：Faction 归属判定
FactionManager fm = CivilServices.getFactionManager();
if (fm != null && spawnerPlayerId != null) {
    Faction factionAtPos = fm.getFactionAt(world, pos);
    Faction spawnerFaction = fm.getPlayerFaction(spawnerPlayerId);
    if (factionAtPos != null && !factionAtPos.equals(spawnerFaction)) {
        return new SpawnDecision(false, 0, SpawnDecision.BRANCH_FOREIGN_TERRITORY);
    }
}
```

| 生成场景 | 生成者 | 结果 |
|---------|--------|------|
| 自己领地 | 自己 | 走原有文明保护（阻止敌怪生成） |
| 他人领地 | 自己 | 等同荒野（不做文明保护） |
| 无主区域 | 任何人 | 走原有 CScore 判定 |
| 自然生成 | `null` | 走原有 CScore 判定（跳过 faction 检查） |

`SpawnDecision` 新增分支常量 `BRANCH_FOREIGN_TERRITORY`。

---

## 九、HUD 增强

`ZoneTransitionPayload` 新增：

```java
String factionName;     // "" 表示无归属
boolean isOwnFaction;   // 是否为玩家的 Faction
```

| 场景 | 文字 | 色条颜色 |
|------|------|---------|
| 自己文明 | `<factionName> · 文明区` | 蓝白 |
| 他人文明 | `<factionName> · 敌对` | 橙色 |
| 荒野 | 不变 | 浅蓝 |
| 警戒 | 不变 | 橙色 |
| 神社 | 不变 | 紫色 |

`PlayerAwarePrefetcher.flushResultReceipts()` 在发送 `ZoneTransitionPayload` 前查询 `FactionManager` 获取 faction 上下文。

`ZoneTransitionHud` 在 render 时根据 `isOwnFaction` 和 `factionName` 切换文字内容和色条。

---

## 十、命令

所有命令挂在 `/civil faction` 下：

```
/civil faction create <name>         创建文明
/civil faction join <name>           加入（需邀请或开放注册）
/civil faction leave                 退出
/civil faction invite <player>       邀请玩家
/civil faction kick <player>         踢出玩家（owner 才能用）
/civil faction transfer <player>     转让所有者（owner 才能用）
/civil faction color <#RRGGBB>       自定义领土颜色（owner 才能用）
/civil faction open                  切换开放注册（owner 才能用）
/civil faction info [name]           查看信息
/civil faction list                  列出所有文明
```

`CivilAdminCommands` 扩展实现。

---

## 十一、改动文件清单

**新增 3 个文件：**

| 文件 | 内容 |
|------|------|
| `src/main/java/civil/faction/Faction.java` | Faction 数据 record |
| `src/main/java/civil/faction/FactionManager.java` | 全生命周期管理 + 空间查询 |
| `src/main/java/civil/aura/AuraPlayerWallRenderer.java` | 独立橙色墙渲染器 |

**修改 24 个文件：**

| 文件 | 改动 |
|------|------|
| `CivilStorage.java` | StoredFaction record；StoredTownCenter/StoredFarmShrine/StoredUndyingAnchor 各加 factionId 字段；新增 loadFactions / writeFactions 接口 |
| `NbtStorage.java` | factions.nbt 读写；三种结构 NBT 增加 factionId 读写（缺省 null 兼容旧档） |
| `CivilServices.java` | +factionManager getter / setter |
| `CivilMod.java` | init() 创建 FactionManager；tick / shutdown 联动 |
| `TownCenterTracker.TownCenterEntry` | +factionId 字段；hasBenefit() 委托 FactionManager 校验 |
| `FarmShrineTracker.ShrineEntry` | +factionId 字段及构造函数 |
| `UndyingAnchorTracker` Entry | +factionId 字段 |
| `TownCenterTracker` | 初始化 / snapshot / 存储方法适配 factionId |
| `FarmShrineTracker` | onShrineActivated 新增 factionId 参数；flush 适配 |
| `UndyingAnchorTracker` | onAnchorActivated 新增 factionId 参数；flush 适配 |
| `TownCenterActivationHandler` | 激活时调用 getOrCreateFactionForPlayer，传 factionId 给 add |
| `FarmShrineActivationHandler` | 激活时调用 getOrCreateFactionForPlayer，传 factionId 给 onShrineActivated |
| `UndyingAnchorActivationHandler` | 激活时调用 getOrCreateFactionForPlayer，传 factionId 给 onAnchorActivated |
| `SpawnPolicy` | decide() 中第 2.5 步插入 faction 判定 |
| `SpawnDecision` | +BRANCH_FOREIGN_TERRITORY 常量 |
| `SonarScan` | +scannerFactionId + vcFactionCache；BFS 时缓存 + 暴露 factionOf() |
| `SonarScanManager.sendBoundaryPacket` | 拆分 ownFaces / foreignFaces 列表 |
| `SonarBoundaryPayload` | +foreignFaces 字段及编解码 |
| `CivilModClientFabric` | 网络接收 + 渲染事件处注册 AuraPlayerWallRenderer |
| `CivilModClientNeoForge` | 同上 |
| `CivilMapTintPalette.ChunkTintEval` | +factionColor 字段 |
| `CivilMapTintPalette.evaluateTintForChunk` | HIGH 时调用 getFactionAt 获取颜色 |
| `CivilMapColorBake` | +blendFillWithColor 方法；调用方适配 factionColor |
| `CivilMapTintUpdateSession` | Context 中携带 factionColor 到 bake |
| `ZoneTransitionPayload` | +factionName +isOwnFaction 及编解码 |
| `PlayerAwarePrefetcher` | flushResultReceipts 发送前查 FactionManager 设上下文 |
| `ZoneTransitionHud` | render 时按 isOwnFaction + factionName 显示不同文字 |
| `CivilAdminCommands` | 新增 /civil faction 子命令 |

**零改动：**

`AuraWallRenderer`、所有 9 个 Mixin、`build.gradle`、`settings.gradle`、`gradle.properties`、Fabric/NeoForge `PlatformImpl`、`SonarChargePayload`、`ModItems`、`ModComponents`、`ModRecipeSerializers`、`ModSounds`。
