package bnbemu.packet.lobby

import bnbemu.netty.PKT

// ============================================================================
// S→C op 86 (0x56)：RoomListUpdate — 大厅房间列表增量更新（空占位实现）
// ============================================================================
// 与 S→C op 87 RoomList（全量房间列表）成对工作；C→S 同编号 op 86 为选色请求 SelectColorReq。
//
// ---- 客户端解析链（reference/CA.exe.c, EN v18）----
// 1) CLobbyStage::OnPacket (0x00510287, CA.exe.c:230791) 在 v5 == 86 时调用
//    sub_510078 (0x00510078, CA.exe.c:230571-230674)，紧邻 op 87 (sub_50FF27)。
//    (CSelectServerStage::OnPacket 0x00625702 无 86 分支，选区阶段收到会忽略。)
//
// 2) 线格式（两段均可独立为空，外层仅当计数非 0 时才写对应 SSD 块）：
//    Encode2(updateCount)     // 第一段：增量修改条数
//    Encode2(addRemoveCount)  // 第二段：新增/删除条数
//    if (updateCount != 0)    Encode4(len1) + ssdEncode(buildSsdBlock(updateStream))
//    if (addRemoveCount != 0) Encode4(len2) + ssdEncode(buildSsdBlock(addRemoveStream))
//
// 3) updateStream（sub_504974, CA.exe.c:221208-221293，小端明文流）：
//    每条先读 u16le roomId + u32le handle；按 sub_505123 查找本地 CGameSessionSlot，
//    要求槽位有效 (+32 != 0) 且 slot.handle (+68) == handle（否则解析进栈上临时对象丢弃）。
//    随后读 u16le mask，按置位顺序更新字段：
//      - 0x001: u8 isPlaying (+108, 0=等待中 / 1=游戏中)
//      - 0x002: u16le unk78 (+120) + u32le mapId (+192)
//      - 0x004: u8 curPlusClosed (+132, 当前人数 + 封闭槽位)
//      - 0x008: u8 closedSlots (+144)
//      - 0x010: u8 maxPlusClosed (+156)
//      - 0x020: str name (u32le len + ACP, 调虚表 +12 设房名)
//      - 0x040: u8 hasPassword (+72)
//      - 0x080: u8 unkA8 (+168)
//      - 0x100: str name + u8 hasPassword (+72) + u8 sessionType (+96) + u8 unk54 (+84)
//      - 0x200: u8 feature67 (+180)
//
// 4) addRemoveStream（小端明文流）：
//    每条先读 u8 isAdd + u16le roomId + u32le handle：
//      - isAdd == 0（删房）：若存在、有效且 handle 匹配，调 sub_504634 清空 (+32 = 0)。
//      - isAdd != 0（建房）：调 sub_504FE4 (CA.exe.c:221486-221535) 读入新房并置 (+32 = 1)，
//        固定默认 isPlaying=0、unk78=255，字段顺序：
//        str name + u8 hasPassword + u8 unk54 + u8 curPlusClosed + u8 closedSlots +
//        u8 maxPlusClosed + u8 sessionType + u32le mapId + u8 unkA8 (+ 特性67 u8 feature67)。
//
// 5) 两段处理完后调用 sub_50FC1C(this, 0) 刷新大厅房间列表 UI。
//
// ---- 抓包验证（cap/decoded_dump.txt 与 cap/practice_s2c_op86_23B_0.bin，全部零剩余字节）----
//   - login.pcap sq=127 (23B): updateCount=1, roomId=56, handle=7020, mask=0x0001, isPlaying=1
//   - login.pcap sq=130/133 (23B): updateCount=1, roomId=67 ("tw"), handle=2311, mask=0x0001, isPlaying=0->1
//   - login.pcap sq=190/193 (23B): updateCount=1, roomId=65, handle=0, mask=0x0001, isPlaying=1->0
//   - login.pcap sq=196 (21B): addRemoveCount=1, isAdd=0, roomId=65, handle=0（删房）
//   - practice_s2c_op86_23B_0.bin (23B): updateCount=1, roomId=71 ("K8-kUn--"), handle=3671, mask=0x0001, isPlaying=1
//
// 当前仅作占位绑定（不调用、不发送），后续实现多房间实时大厅刷新时再填充编码体。
// ============================================================================
fun RoomListUpdate() = PKT {
    // 占位空实现：仅占用 S→C op 86
}
