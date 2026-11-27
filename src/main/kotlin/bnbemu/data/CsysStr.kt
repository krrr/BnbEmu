package bnbemu.data

import bnbemu.codec.buildSsdBlock
import bnbemu.codec.ss
import bnbemu.codec.ssdEncode
import bnbemu.codec.u16le
import java.io.ByteArrayOutputStream

// ============================================================================
// S→C op 2：csysstr.ssd 系统字串字典（首连推送 #2，start.pcap sq=46，1861 B）
// ============================================================================
// 归属已按客户端反编译钉死：CStringMan（不是 CQuestStringMan，后者是 op 67 / csysqstr.ssd）。
//   reference/CA.exe.c:556772 sub_682B7C = 首连握手，先发 op 0（带 5 个 revision），
//   再**硬编码**顺序等 193 → 2 → 67 → [61] → 1 → 51；:556805 判 opcode==2 后
//   调 sub_6C406B(CStringMan 单例 dword_993E50)。客户端还会校验包序，缺包即判登录失败。
//   :615262 sub_6C406B 收包：ReadUInt8 作 hasNew —— 1 = 后面跟资料块（解析+比对校验和+写盘），
//   0 = 复制本地缓存流(this+17)后解析，不写盘（此时客户端沿用本地 csysstr.ssd）。
//   :615187 sub_6C3ED6 解析：ReadUInt16 取条数，循环 ReadString(key)/ReadString(value)
//   （sub_78CE60，u32 LE 串长），key 经 sub_75C7E0 逐字符 _mbctolower 后**逐条 merge**
//   进字典 this+3（`sub_57E47F` = map operator[]，不清表；覆盖同名 key、保留本地既有条目）。
//   注意 sub_6B8F7E 会把**收到的这一块**（不是合并后的全表）写回本地 csysstr.ssd：
//   == 所以服务端发的表等于客户端磁盘缓存的新基准，缺的 key 下次启动也补不回来。
//   :615148 sub_6C3E2B 构造 CStringMan：:615164 拼 "csysstr.ssd" → sub_6B9141 读盘。
//   :605273 sub_6B8F7E 写盘：只有块校验和变化才 sub_6B8E3F 覆写文件。
//
// 包体（payload + 2 B opcode；抓包基准 cap/firstconn_s2c_op2.bin 为 1861 B / 38 条）：
//   Encode1Bool(true)       // hasNew = 1
//   Encode4(块长)            // = 下面 SSD 块字节数
//   EncodeBuffer(块)         // = ssdEncode(buildSsdBlock(plain, compress = false))
// 块 = [0x18][u32 LE 块长-5][compress=0][plain]：
//   plain = u16 LE count + count × [ u32 LE keyLen | key | u32 LE valLen | value ]
//   抓包那版 1854 = 4×463 + 2，末 2 字节走 ssdEncode 的 0xA569 分支（sub_75DA10 :709321）：
//   明文 ". " → 线上 `85 47`（0x8547 ^ 0xA569 = 0x202E → LE ". "）。
//   ⚠ 早前笔记/工具把这两字节当「原样保留」，解出来是乱码；客户端一直接 4/2/1 三档解码。
//   本模拟器在抓包 38 条之后追加了 44 条激战台词（见下方 AI_BATTLE_SAY），包长随之变大。
//
// 为什么 compress 固定 false：真服就是发未压缩块（抓包 compress=0）。客户端把这个未压缩块的
// 校验和（[compress..末端] 的 Adler-32 变体，sub_78BB60 :747990）与本地文件比对，而本地文件由
// 客户端自己压成 compress=1 后存盘，两者本来就不会相等 —— 所以真服也永远发 hasNew=1，
// 客户端每次登录重写一次内容相同的 csysstr.ssd。改成 compress=true 会偏离基准且没有收益。
//
// 版本号（供 op 0 / op 55 参考，本模拟器暂不上报）：客户端上报的是 CStringMan+92，
// 即 sub_6B8F7E 写入的块校验和。抓包 op 0 的 5 个 u32 解出 [0, 0x1255CF6D, 0, 0xA8350869,
// 0x380A0583]：第 2 个正是本地 csysstr.ssd 的 compress=1 块校验（已复算一致），
// 第 1、3 个为 0 是因为本地没有 fxdynimginfo.ssd / csysqstr.ssd，
// 第 4、5 个是 multisvr（改动 IP 前）与 filterstr 的版本戳。
//
// 表内容 = 运营可热更的文案子集，不是全部 UI 文本（例如 "ErrCannotLogin" 就不在表内，
// 它在客户端自带 ClientData/ClientString 里）。key 由代码动态拼：CCharacterNameMan 用
// "Ch%02d"（:181615）取角色名，频道描述用 "ChannelDesc%d"（:187140），激战 NPC 台词用
// "AIBattleSay_*"（:354613）；值里的 %s/%d 由客户端在显示时格式化
// （"JoinSession" = "%s has joined.\n" 就是大厅系统消息）。
// ============================================================================

/** csysstr.ssd 的一条 key→文案；key 在客户端会转小写后建索引，查询大小写不敏感。 */
data class SysStrEntry(val key: String, val value: String)

/** 抓包原表的 38 条（顺序即线上顺序；这段是 cap/firstconn_s2c_op2.bin 的逐字节基准，勿改）。 */
internal val CAPTURE_SYS_STR: List<SysStrEntry> = listOf(
    SysStrEntry("Wriggle", "Trap and pop goobies to send them to the other team!\nSend the most goobies to win!"),
    SysStrEntry("MissionWriggleMsg", "Pop goobies to send them to the other team!\nSend the most goobies to win!"),
    SysStrEntry("Ch14", "Posh Dao"),
    SysStrEntry("Ch18", "Posh Diz"),
    SysStrEntry("Ch17", "Posh Moss"),
    SysStrEntry("LuxuryKephi", "Posh Plunk"),
    SysStrEntry("SuperUni", "Posh Ike"),
    SysStrEntry("RoomGameTypeSelectToolTip", "Filtered Selection"),
    SysStrEntry("ChannelDesc0", "Come on in!"),
    SysStrEntry("WriggleRandom", "Trap and pop goobies to send them to the other team!\nSend the most goobies to win!"),
    SysStrEntry("NoItemFound", "The item you are looking for has not been found or is not currently available. "),
    SysStrEntry("QuestStart", "Start"),
    SysStrEntry("QuestStop", "Stop"),
    SysStrEntry("SquareToolTipGBB", "Rock Paper Scissors"),
    SysStrEntry("SquareToolTipAIBattleGuide", "Battle Mode Tips"),
    SysStrEntry("SquareToolTipPlayGame", "Find Match"),
    SysStrEntry("SquareToolTipFriendSearch", "Friend Search"),
    SysStrEntry("Luca", "Luca is the BEST!!"),
    SysStrEntry("CreateDlgGameTypeAll", "All"),
    SysStrEntry("CreateDlgGameTypeNpcBattle", "Monster"),
    SysStrEntry("WannaLeaveLadderRoom", "CRAZYARCADE orbs collected in this room will be lost. Are you sure you want to leave?"),
    SysStrEntry("ChannelDesc50", "Test your skill!"),
    SysStrEntry("Gather11Balls", "Collect all CRAZYARCADE orbs for a bonus!"),
    SysStrEntry("ShowWaitRoomToolTip", "Only show open rooms"),
    SysStrEntry("ShowAllRoomToolTip", "Show all rooms"),
    SysStrEntry("CreatePracticeRoomToolTip", "Practice your moves!"),
    SysStrEntry("ChatIgnoreToolTip", "Ignore List"),
    SysStrEntry("ShoutToolTip", "Megaphone"),
    SysStrEntry("UserRejectJoinSession", "You cannot join this room."),
    SysStrEntry("SSNMismatched2", "Your inventory is full. Please delete items in order to buy more. "),
    SysStrEntry("SquareMsgDisconnect", "Disconnected from Town. Please try again."),
    SysStrEntry("LobbyButton", "Return To Lobby"),
    SysStrEntry("ConnectedTo", "Online - %s"),
    SysStrEntry("ConnectedTo2", "Online - %s [%s]"),
    SysStrEntry("JoinSession", "%s has joined.\n"),
    SysStrEntry("LeaveSession", "%s has left.\n"),
    SysStrEntry("MasterJoinSession", "[Ladder Grade %d %s has joined.]\n"),
    SysStrEntry("CannotJoinOverflow", "This channel is full. Please join another channel. "),
)

// ============================================================================
// 激战模式 NPC 台词补全（44 条，追加在抓包表之后）
// ============================================================================
// 现象：房间/对局里 NPC 说话显示成 "AIBattleSay_Prepare_2_1" 这类内部 key。
//
// 成因链（全部按客户端反编译核对）：
//   1. C→S op 156 / S→C op 270 只带 3 个数字（slot / event / parameter），**从不带文案**；
//   2. 文案是收包端自己拼 key 再查字典 —— `sub_599941`(:354613) 拼
//      "AIBattleSay_Prepare_%d_%d" 等 8 种格式（%d = NPC 序号 type-20，%d = 台词序号），
//      `sub_598683`(:253125)/`sub_52C1E7`(:253060) 拿它查 CStringMan (dword_993E50)；
//   3. 查不到就落回 key 本身（`sub_57E47F` 的 map[] 语义），于是聊天栏印出内部 key。
//
// 为什么查不到：CStringMan 只有两个来源 ——
//   a) 客户端自带的 ClientData/ClientString（fx.idd 内 4796 条，含 AIBattleNPCName00..03，
//      所以 NPC 名字正常）；**实测该表里一个 AIBattleSay_* 都没有**（CA.exe 里只有格式串）；
//   b) 服务端 op 2 推送的 csysstr.ssd（本表）——EN 抓包里 38 条也没有。
// 即：这版 EN 客户端压根没随包发布这些台词，官方当年的做法只可能是走 csysstr 热更（op 2 是
// 逐条 merge 进 CStringMan 的，"运营可热更文案"就是它的用途）。本模拟器沿用这条通道补全。
//
// key 取值域（`sub_598ECE` :353838 / `sub_5990FB` :354073 定死的随机取值）：
//   - event 0..6（AIDie/UserDie/AIUseItem{Needle,Shield,Spring}/AIWin/AILose）parameter 恒 0；
//   - event 8（Prepare）parameter 0..3（按权重抽一句，见 sub_598ECE 的 7/12/3/7 分布）；
//   - NPC 序号 0..3（type 20..23 = G-sta Zombie / Thrilla / Zombie Sue / Mr. Ghoost；
//     type 24..26 的 sub_598ECE 直接返回 -1，不发言）。
// 因此正好 8 种事件 × 4 个 NPC（Prepare 各 4 句）= 28 + 16 = 44 条。
//
// ⚠ 文案是本模拟器补写的（真服原文无从取得），只想改措辞时**只改 value、不增删条目**；
//   若要恢复"与抓包逐字节一致"的登录包，把 EN_SYS_STR 换回 CAPTURE_SYS_STR 即可。
internal val AI_BATTLE_SAY: List<SysStrEntry> = listOf(
    // ---- Prepare（房间准备阶段 / 开局前，每 NPC 4 句）----
    SysStrEntry("AIBattleSay_Prepare_0_0", "Yo! Let's get this started!"),
    SysStrEntry("AIBattleSay_Prepare_0_1", "Ready to get popped?"),
    SysStrEntry("AIBattleSay_Prepare_0_2", "I'm taking this one, easy!"),
    SysStrEntry("AIBattleSay_Prepare_0_3", "Come on, bring it!"),
    SysStrEntry("AIBattleSay_Prepare_1_0", "Let's see what you've got."),
    SysStrEntry("AIBattleSay_Prepare_1_1", "I hope you're not scared."),
    SysStrEntry("AIBattleSay_Prepare_1_2", "I'll try not to hurt you."),
    SysStrEntry("AIBattleSay_Prepare_1_3", "Don't disappoint me."),
    SysStrEntry("AIBattleSay_Prepare_2_0", "Let's play! Come on!"),
    SysStrEntry("AIBattleSay_Prepare_2_1", "I'm ready! Are you?"),
    SysStrEntry("AIBattleSay_Prepare_2_2", "I'll do my best!"),
    SysStrEntry("AIBattleSay_Prepare_2_3", "Ready or not, here I come!"),
    SysStrEntry("AIBattleSay_Prepare_3_0", "Boo! Did I scare you?"),
    SysStrEntry("AIBattleSay_Prepare_3_1", "Let's play... forever."),
    SysStrEntry("AIBattleSay_Prepare_3_2", "The dark is my friend."),
    SysStrEntry("AIBattleSay_Prepare_3_3", "Welcome to my nightmare."),
    // ---- AIDie（AI 自己被打倒）----
    SysStrEntry("AIBattleSay_AIDie_0_0", "Aw man! I got popped!"),
    SysStrEntry("AIBattleSay_AIDie_1_0", "Heh... not bad, kid."),
    SysStrEntry("AIBattleSay_AIDie_2_0", "Waaah! No fair!"),
    SysStrEntry("AIBattleSay_AIDie_3_0", "Even ghosts... can lose..."),
    // ---- UserDie（玩家被 AI 打倒）----
    SysStrEntry("AIBattleSay_UserDie_0_0", "Ha! Told ya I'm the best!"),
    SysStrEntry("AIBattleSay_UserDie_1_0", "Too slow, too slow."),
    SysStrEntry("AIBattleSay_UserDie_2_0", "Yay! I win!"),
    SysStrEntry("AIBattleSay_UserDie_3_0", "Your soul is mine. Just kidding."),
    // ---- 使用道具（针 / 盾 / 弹簧）----
    SysStrEntry("AIBattleSay_AIUseItemNeedle_0_0", "Catch me if you can!"),
    SysStrEntry("AIBattleSay_AIUseItemNeedle_1_0", "Zip! Out of my way."),
    SysStrEntry("AIBattleSay_AIUseItemNeedle_2_0", "Wheee! Here I go!"),
    SysStrEntry("AIBattleSay_AIUseItemNeedle_3_0", "You can't catch a ghost."),
    SysStrEntry("AIBattleSay_AIUseItemShield_0_0", "Ha! Can't touch this!"),
    SysStrEntry("AIBattleSay_AIUseItemShield_1_0", "Try harder, loser."),
    SysStrEntry("AIBattleSay_AIUseItemShield_2_0", "Nope! Not today!"),
    SysStrEntry("AIBattleSay_AIUseItemShield_3_0", "Nothing gets through me."),
    SysStrEntry("AIBattleSay_AIUseItemSpring_0_0", "Boing! See ya up there!"),
    SysStrEntry("AIBattleSay_AIUseItemSpring_1_0", "Up, up and away."),
    SysStrEntry("AIBattleSay_AIUseItemSpring_2_0", "Wheee! So high!"),
    SysStrEntry("AIBattleSay_AIUseItemSpring_3_0", "Come fly with me... if you dare."),
    // ---- 结算（AI 胜 / AI 负）----
    SysStrEntry("AIBattleSay_AIWin_0_0", "Yeah! Nobody beats G-sta!"),
    SysStrEntry("AIBattleSay_AIWin_1_0", "That was almost fun."),
    SysStrEntry("AIBattleSay_AIWin_2_0", "Yay! I'm the best!"),
    SysStrEntry("AIBattleSay_AIWin_3_0", "The dark always wins."),
    SysStrEntry("AIBattleSay_AILose_0_0", "No way! Rematch, rematch!"),
    SysStrEntry("AIBattleSay_AILose_1_0", "Hmph. You got lucky."),
    SysStrEntry("AIBattleSay_AILose_2_0", "Waaah! I wanna go again!"),
    SysStrEntry("AIBattleSay_AILose_3_0", "I'll be back... I always am."),
)

/** 实际下发的表 = 抓包 38 条 + 激战台词补全（客户端按 key 逐条 merge，顺序无所谓）。 */
internal val EN_SYS_STR: List<SysStrEntry> = CAPTURE_SYS_STR + AI_BATTLE_SAY

/**
 * csysstr.ssd 单一事实源：38 条文案全部在这里定义，包体实时派生。
 * 条数变化会自动改包长，不需要同步任何 hex 常量。
 */
object CsysStr {

    /** 抓包原表 38 条（cap/firstconn_s2c_op2.bin 的逐字节基准）。 */
    val captureEntries: List<SysStrEntry> = CAPTURE_SYS_STR

    /** 实际下发表（38 条抓包 + 44 条激战台词），顺序即线上顺序。 */
    val entries: List<SysStrEntry> = EN_SYS_STR

    /** csysstr.ssd 缓存体明文：u16 LE 条数 + 逐条两个 u32 LE 长度前缀串。 */
    val plain: ByteArray by lazy { serializePlain(entries) }

    /** 未编码的 SSD 块：[0x18][u32 LE 块长-5][compress=0][plain]。 */
    val block: ByteArray by lazy { buildSsdBlock(plain, compress = false) }

    /** 上线的块字节（ssdEncode 的 4/2/1 三档变换结果），供 FirstInfoInit 直接下发。 */
    val wireBlock: ByteArray by lazy { ssdEncode(block) }

    /** 序列化缓存体；条数上限是线上 u16，超了客户端会截断。 */
    fun serializePlain(entries: List<SysStrEntry>): ByteArray {
        require(entries.size <= 0xFFFF) { "csysstr 条数 ${entries.size} 超出 u16 上限" }
        val w = ByteArrayOutputStream()
        w.u16le(entries.size)
        for (e in entries) {
            w.ss(e.key)
            w.ss(e.value)
        }
        return w.toByteArray()
    }
}
