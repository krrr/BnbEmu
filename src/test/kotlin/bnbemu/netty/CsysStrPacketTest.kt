package bnbemu.netty

import bnbemu.codec.buildSsdBlock
import bnbemu.codec.ssdEncode
import bnbemu.data.CsysStr
import bnbemu.data.CsysStr.serializePlain
import bnbemu.data.SysStrEntry
import bnbemu.packet.FirstInfoInit
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S→C op 2 FirstInfoInit（csysstr.ssd 缓存体）程序化生成的回归基准。
 *
 * 抓包基准 = cap/firstconn_s2c_op2.bin（1861 B，含 2 B opcode / 38 条）：
 *   Encode1Bool(true) + Encode4(1854) + ssdEncode(块)，
 *   块 1854 B = [0x18][u32 LE 1849][compress=0][plain 1848]，
 *   plain = u16 LE 38 + 38 × [u32 LE keyLen | key | u32 LE valLen | value]。
 * 明文末 2 字节走 ssdEncode 的 0xA569 尾块分支（客户端 sub_75DA10 :709321），
 * 不是“原样保留”——见 `尾部两字节走 0xA569 而非原样保留`。
 *
 * 线上实发的表在抓包 38 条之后追加了 44 条激战台词（CsysStr.AI_BATTLE_SAY），
 * 所以“逐字节一致”只对 `CsysStr.captureEntries` 这一子集成立。
 */
class CsysStrPacketTest {

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** 按客户端 sub_6C3ED6 的读法把 plain 解回条目，验证序列化可逆。 */
    private fun parsePlain(plain: ByteArray): List<SysStrEntry> {
        val count = u16le(plain, 0)
        var off = 2
        fun readStr(): String {
            val len = u32le(plain, off)
            val s = String(plain, off + 4, len, Charsets.ISO_8859_1)
            off += 4 + len
            return s
        }
        return List(count) { SysStrEntry(readStr(), readStr()) }
    }

    @Test
    fun `明文按客户端读法可无损往返`() {
        val plain = CsysStr.plain
        assertEquals(38, CsysStr.captureEntries.size, "抓包原表 38 条")
        assertEquals(CsysStr.entries.size, u16le(plain, 0), "明文开头 u16 count 必须等于条目数")
        assertEquals(CsysStr.entries, parsePlain(plain), "key/value 顺序与内容必须原样还原")
    }

    @Test
    fun `块头是 csysstr 的未压缩 SSD 块`() {
        val block = CsysStr.block
        assertEquals(0x18, block[0].toInt() and 0xFF, "SSD 块魔数")
        assertEquals(block.size - 5, u32le(block, 1), "块头 u32 长度 = 块长-5")
        assertEquals(0, block[5].toInt() and 0xFF, "真服发 compress=0，客户端自己压成 compress=1 存盘")
        assertArrayEquals(CsysStr.plain, block.copyOfRange(6, block.size), "compress=0 时负载就是明文")
    }

    @Test
    fun `尾部两字节走 0xA569 而非原样保留`() {
        // 抓表明文末条 "…another channel. " 的最后两字节 2E 20 → 0x202E ^ 0xA569 = 0x8547 → BE 85 47
        val wire = wireOf(CsysStr.captureEntries)
        assertEquals(0x85, wire[wire.size - 2].toInt() and 0xFF)
        assertEquals(0x47, wire[wire.size - 1].toInt() and 0xFF)
        val dec = ByteArray(2)
        val be = ((wire[wire.size - 2].toInt() and 0xFF) shl 8) or (wire[wire.size - 1].toInt() and 0xFF)
        val v = be xor 0xA569
        dec[0] = v.toByte()
        dec[1] = (v ushr 8).toByte()
        assertArrayEquals(". ".toByteArray(), dec, "末 2 B 应还原成明文 \". \"")
    }

    @Test
    fun `程序化重建与抓包逐字节一致`() {
        val wire = wireOf(CsysStr.captureEntries)
        val body = byteArrayOf((0x01 xor 0x5A).toByte()) + enc4(wire.size) + wire
        assertEquals(1859, body.size, "1 + 4 + 1854 = 1859 B 包体（不含 2 B opcode）")
        assertEquals(1854, u32beXor(body, 1), "Encode4 块长须为 1854")

        val cap = File("cap/firstconn_s2c_op2.bin").takeIf { it.isFile } ?: return
        assertArrayEquals(cap.readBytes().drop(2).toByteArray(), body, "必须与 cap/firstconn_s2c_op2.bin 一致")

        // 实际下发的包 = 抓包表 + 激战台词，必须比基准大（且仍是同一编码路径）
        val live = encode(FirstInfoInit())
        assertEquals(CsysStr.wireBlock.size + 5, live.size, "1 + 4 + 块长")
        assertTrue(live.size > body.size, "追加 44 条台词后包体应大于抓包基准")
    }

    @Test
    fun `激战台词 44 条齐全且 key 可被客户端解析`() {
        val keys = CsysStr.entries.map { it.key.lowercase() }   // 客户端 sub_75C7E0 会把 key 小写化
        assertEquals(keys.size, keys.toSet().size, "key 不得重复（小写后比较）")

        val events = listOf(
            "AIDie", "UserDie", "AIUseItemNeedle", "AIUseItemShield",
            "AIUseItemSpring", "AIWin", "AILose",
        )
        val expected = buildList {
            for (e in events) for (n in 0..3) add("aibattlesay_${e}_${n}_0".lowercase())
            for (n in 0..3) for (p in 0..3) add("aibattlesay_prepare_${n}_$p")
        }
        assertEquals(44, expected.size)
        for (k in expected) assertTrue(k in keys, "缺少客户端会拼的 key: $k")

        assertEquals(82, CsysStr.entries.size, "38 条抓包 + 44 条台词")
        for (e in CsysStr.entries) {
            assertTrue(e.value.isNotBlank(), "${e.key} 的文案为空，客户端会显示成空行")
            assertTrue(e.key.all { it.code < 0x80 } && e.value.all { it.code < 0x80 },
                "${e.key} 含非单字节字符，ss() 走 ACP 编码会串味")
        }
    }

    private fun enc4(v: Int): ByteArray {
        val x = v xor 0x96CA5395.toInt()
        return byteArrayOf((x ushr 24).toByte(), (x ushr 16).toByte(), (x ushr 8).toByte(), x.toByte())
    }

    private fun u32beXor(b: ByteArray, off: Int): Int {
        val be = ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
        return be xor 0x96CA5395.toInt()
    }

    @Test
    fun `改一条条目包长随之变化`() {
        val oneMore = CsysStr.entries + SysStrEntry("ProbeKey", "Probe Value")
        val plain = CsysStr.serializePlain(oneMore)
        assertEquals(CsysStr.plain.size + 4 + "ProbeKey".length + 4 + "Probe Value".length, plain.size,
            "多一条就多一条的长度前缀与内容，说明包体不是硬编码常量")
        assertNotEquals(CsysStr.plain.size, plain.size)
    }

    /** 任意条目集 → 线上块字节。 */
    fun wireOf(entries: List<SysStrEntry>): ByteArray =
        ssdEncode(buildSsdBlock(serializePlain(entries), compress = false))
}
