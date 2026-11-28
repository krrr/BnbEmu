- **进入游戏商城流程（C→S op 315）**：参见EnterShopReq.kt
- **商城购买（C→S op 267 / S→C op 202）**：参见 `handler/shop/BuyItemReq.kt` + `packet/shop/BuyItemResult.kt`。
  请求体 = `E4(prodId, 即 op65 目录的商品号) + E4(arg) + E1(flag)`（实测点 "Bomb 655"：
  `A1 A4 FC 9A | 96 CA 53 95 | 5A` → prodId=930000655）；发完客户端置等待锁 `[shop+0xC0]=1`。
  ⚠️ 结果包 opcode = **202**：商城舞台 `CShopStageNew::OnPacket` (`sub_5D5CBA`) 里
  `v7 = opcode - 202; if (v7 == 0) → sub_5D171B(this, pkt)` 才是购买结果处理；
  op 267（有专属 case 直接 return）和 op 268（只落到 `default: goto LABEL_16` 的
  购物篮/优惠券广播）都到不了商城舞台。
  结果包体 = `E1(type) + E4(到期时间 hi) + E4(lo)`（**不含** 202，202 就是 opcode）；
  `sub_5D171B` 先用 `sub_66B4ED` 读 type 再读 8 字节到期时间（0=永久），
  然后按客户端自己选中的商品调 `sub_66A017` 把物品应用进背包；随后 `sub_5CDC64` 发
  **C→S op 294**（`E1(playerIdx)`，实测无附加负载）并回发 **C→S op 273**（action=2 的完整变更清单，
  见上一条）。两者都是纯通知，服务端不需要回包。
- **商城背包同步（C→S op 294）**：参见 `handler/shop/ShopInvenSyncReq.kt`，纯上报，读掉 playerIdx 即可。
- **背包全量同步只能走 S→C op 198（不是 320）**：客户端 `CUserInventoryMan::ReadEquippedInventory`
  (`0x67601D`) 会先**清空**两位玩家的背包/装备，再由 `ApplyEquippedInventory` (`0x6757A6`)
  逐条 `sub_65D55F` 重建 —— op 198 是背包的唯一真相源。背包物品用伪槽位 **0xFF**
  （`CUserInventory::AddItem` 0x65D55F 里 `slot == -1` 且分类非可穿戴集合 → 只 push 进背包向量 `[inv+8]`），
  令牌取 op 60 定义表里的 name。实现在 `packet/UserInventory.kt` 的 `bagInventoryEntries()`，
  `JoinSessionReq`（进频道）与 `StageInitReq`（回大厅，必跟在 198 之后补 413 保住背包等级）都会带上。
- **S→C op 320 是商城专用单件入包，不是背包列表**：`case 0x140 → sub_4FCCBF`，包体 =
  `E1(playerIdx) + E4(itemId) + E4(param) + E4(hours) + E1(a) + E1(b)`，itemId 用
  `sub_677912(dword_993D58（商城商品管理器）, ...)` 查定义 —— 在大厅/频道里查不到 → 整条静默丢弃。
  所以别拿它做背包持久化（这是「商城里买完看得到、回大厅就没了」的根因）。
- **商城连线状态上报（C→S op 316）**：`sub_5BE296` → `E1(state)`，退出商城回大厅时
  `sub_5DF695(a2==8)` 发出，纯通知无回包。见 `handler/shop/ShopConnStateReq.kt`。
  错误码走 `sub_630625` 弹窗（-32 金币不足 / -33 超上限 / -47 已拥有 / -48 背包已满 / -100 道具锁）。
- **退出商城时的返回舞台**：
  1. 客户端 `CShopStageNew::ExitShop` (`0x005CDA35`) 发 C→S op 97 (`E1(0)`)，随后等
     **S→C op 97 `ChangeStage(E2(stageId))`**；处理器是 `sub_5CDD15` (`0x005CDD15`，
     `CShopStageNew::OnPacket` `0x005D5CBA` 的 case 97)，不是 `sub_4F895D`。
  2. `sub_5CDD15` 的分派：
     - `sub_4F748F(stageId)`（`0x004F748F`，`stageId ∈ {12,16,17,18}`）→ 发 **C→S op 80**
       (`E2(sessionShort) + E4(sessionId)`，纯通知不等回包) 后 `SetStage(stageId)` **回房间**；
     - 否则 `sub_67CDCE()` 为真 → `SetStage(20)` 广场，普通模式 → `SetStage(11)` 大厅 + 自动发 C→S op 78。
     ⇒ 从大厅进商城要回 `ChangeStage(11)`，从房间进商城必须回 `ChangeStage(12)`；
     固定填 11 就是「房间内进商城点返回直接回大厅」的根因。op 155 BackToRoom 只挂在
     stage 13 对局分发器上，商城舞台收不到，不能拿来替代。
  3. 服务端实现：`ClientSocket.stage / shopReturnStage` 跟踪当前与进商城前的舞台
     （由 `OnConnEstablished`→11、`CreateSessionReq`→12、`Room.backToRoom`→12、
     `StageInitReq`→11、`EnterShopReq`→14 并保存来源、`ShopToRoomReq`(op 80)→房间 维护），
     `StageStateSyncReq` 在 `stageState == 0` 时按 `shopReturnStage` 下发 `ChangeStage(12/11)`。
     见 `handler/StageStateSyncReq.kt` / `handler/shop/EnterShopReq.kt` /
     `handler/ShopToRoomReq.kt` / `packet/ChangeStage.kt`，回归用例 `ShopTest.kt`。
- **C→S op 80 不是「商城回房间专用」但触发条件唯一**：
  - 550 个 CSendPacket 构造调用点里，`push 0x50` + `call CSendPacket_CtorWithOp(0x0064EE1C)`
    只有 **2 处**：`0x005CDD3D`（`sub_5CDD15`）与 `0x0062F750`（`sub_62F728`），代码逐字节相同；
    前置窗口放宽到 320B 复核 `mov reg,0x50 → push reg` 形式仍无第三处。
  - 两者分别是 `sub_5D5CBA`（虚表 `0x008AF174`）与 `sub_631C78`（虚表 `0x008B441C`）的
    **op 97 case**；两张虚表槽位一一对应，是同一舞台基类的两套平行商城舞台实现。
  - `sub_4F748F` 全二进制只有 4 个调用者：这 2 个发 op 80，另 2 个（`CShopStage_OnEnter` /
    `sub_6300BD`）是这两套舞台的构造/初始化，不发。
  ⇒ op 80 的语义是「**收到 S→C op 97 且目标 stage 是房间舞台(12/16/17/18) 时的会话回报**」，
  任何 ChangeStage 回房间的路径都会发；商城退出只是目前服务端会触发的那一条。
  发完 `CSendPacket_Send` 立刻 `SetStage`，**无等待锁、不期待 S→C op 80**；
  EN 建房走 op 75、进房结果走 op 83，`packet/CreateSessionResult.kt` 无任何发送点，
  所以 `ShopToRoomReq` 只消费 `E2+E4`、不回包是安全的。
- **商城三份本地缓存（S→C op 65 / 66 / 204 与 C→S op 276 / 258 / 278）**：
  `CShopItemMan`（单例 `dword_994D08`）按 `EnterShopStage` 的三个版本号校验本地 ssi 缓存，缺失/不符就各自请求：
  `fxstock.ini` ↔ op 65（`sub_670965`）/ C→S 276；`packagemap.ini` ↔ op 66（`sub_670BD6`）/ C→S 258；
  `stringtable.ini` ↔ op 204（`sub_670A9D`）/ C→S 278。三者封包同构：
  `E4(块大小) + ssdEncode(0x18 智能流块)`，块 = `[0x18][u32le 体长][压缩标志][体]`。
  - op 66 体 = `u16le(条数) + N × [u32le(套餐 itemId) + u32le(子商品 itemId)]`，供 `sub_666D3A`/`sub_666CCE`
    把套餐（item 类别 11 / 0x15–0x1A）展开成单品；实现 `packet/ShopPackageMap.kt`（`PackageMapEntry`）。
  - op 204 体 = `u16le(条数) + N × [u32le(id) + u32le(字串长) + 原文]`；实现 `packet/ShopStringTable.kt`。
  - 空表块恒为 `18 03 00 00 00 00 00 00`，所以抓包里 op 66 与 op 204 除 opcode 外逐字节相同。
  - ⚠️ 空表落盘 `adler32Zero(体) = 0`，而 `sub_670D66` / `sub_670E05` 要求版本号非 0，客户端每次进商城
    都会重发 258/278 再要一次缓存——预期行为，服务端必须每次回包。
- **商城角色预览/选角只认部分 id（进商城变 Dao 的根因，2026-11-25）**：
  `CShopStage_OnEnter`（`0x5D1106`）先用 `CUserDynamicInfo_GetBomber`（`0x6E50F0`）读出本机
  当前角色，写入 `[shop+0x2F5C]`（=12124），随后 `CShopStage_SelectBomber`（`0x5CEA57`，
  选角/初始化，`Shop/S_DlgSelecChar`）和 `CShopStage_SetBomberPreview`（`0x5CE3D4`，
  预览模型：`412*id + CBomberAttr + 0x6C` 资源组路径）都会做白名单：
  - `CBomberAttr_IsNormalBomber`（`0x519680`）= `0 <= id < CBomberAttr_GetNormalBomberLimit()`，
    运行时实测 `CBomberAttr_GetNormalBomberLimit`（`0x4D65D3`）=
    `32 - 10(cat3) - 9(cat2) - 0(cat1) - 2(cat4) = 11` → **0..10**；
  - `CBomberAttr_IsSkinBomber`（`0x51969C`）= `[10, 10 + 9)` → **10..18**。
  两者都不命中时，`CShopStage_SelectBomber` 把 `v3` 清 0、`CShopStage_SetBomberPreview`
  回退 `[shop+0x773C]`（初始 0 = Dao）。
  于是 **id 19（C22，attr 分类 5）和 20..31 在商城里一律显示 Dao**；8/9 因 `<11` 反而正常，
  10..18 因落在皮肤区正常。`CUserDynamicInfo_SetBomber`（`0x6E4BAB`）写进 CUDI 的仍是真实 id，
  而「我的角色」/名片走 `sub_672024`（>7 原样返回）读 CUDI，所以名片显示正常——这就是
  「只有商城坏、状态没改」的来源。
  - 客户端补丁 `tools/client/patch_shop_char.py`：两处判定改成 `0 <= id < 32`（保留 0..7 的
    角色卡映射与非法 id 的兜底），VA `0x5CE3E2`（26B）/ `0x5CEAA0`（15B），备份 `CA.exe.shopbak`。
  - ⚠️ 打 `0x5CEAA0` 时必须补 `add esp, 4`：原 `call CBomberAttr_IsSkinBomber` 的 `ret 4`
    会吃掉 `0x5CEA88` 的 `push edi`；直接 NOP 掉调用会让 SEH 收尾（`0x71DC24` 的
    pop edi/esi/ebx）整体错位，返回时 EBX=0，调用方 `CShopStage_OnEnter` 紧接着
    `CShopStage_ApplyBomber(this=0)` 必崩（实测崩在 `0x46BFE1`，`this=0x3520 = 0x2F74+0x5AC`）。
  - 个人交易市场有同构代码（预览 `sub_6A7823` @ `0x6A7831`，选角 `sub_6A7B99` @ `0x6A7BDC`），
    目前未纳入补丁；若市场同样变 Dao，按相同思路处理（同样注意栈平衡）。
