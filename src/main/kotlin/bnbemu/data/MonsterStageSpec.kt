package bnbemu.data

/**
 * 怪物模式（StageClear / Mode 14）单只怪物模板，直接从 `resource/fx/Map/<name>.bin` 尾部的
 * `<StageClearScript>` XML 解析，作为唯一事实源。
 *
 * @property id              客户端 `CNetObjectMan` 紧凑网络对象 ID：
 *                           `initial == true` 的初始怪密集编号 `0 until initialCount`，
 *                           `initial == false` 的脚本模板紧随其后（仅用于按 [scriptClass] 查找）。
 * @property type            `<InitInfo type="..."/>`（如 `basicMon`、`iceMon`、`StarFishMon`、
 *                           `PudadakMon`、`bossMon1`、`BossMon2`、`BossTurtle`、`bossRobot`、`bombmissile` 等）
 * @property hp              `<InitInfo life="Max=..."/>`，恒 >= 1
 * @property angerHp         `<InitInfo life="Anger=..."/>`，Boss 血量降到此值及以下时触发狂暴（0 = 无狂暴）
 * @property initial         `<InitInfo fromStart="true|1"/>`：开局即生成在棋盘上
 * @property boss            `<InitInfo boss="true|1"/>`：Boss 角色（血量归 0 进入锁泡态而非直接死亡）
 * @property scriptClass     `<InitInfo class="..."/>`：动态刷怪 (C136) 24B init 块偏移 16..19 的查找键；缺省退回 [id]
 * @property influencesClear `<InitInfo NoInfluenceGameEnd="1"/>` 取反：false 表示无限刷新怪，不计入通关目标
 * @property settlesOnDefeat 终结型动态海星（`StarFishMon`、`!initial` 且无 `<Trigger Type="selfdie"/>`）：
 *                           确认死亡后客户端可能不发 C137，死亡即视为目标完成
 * @property maxSelfDamage   该 `<ScriptObject>` 声明的最大 `<Action ... selfDamage="N"/>` 数值（0 = 未声明），
 *                           仅用于允许 `iceMon` 的脚本自伤（如 `MonPen08` 波次生成器）绕过冰冻前置条件
 */
data class MonsterTemplate(
    val id: Int,
    val type: String,
    val hp: Int,
    val angerHp: Int = 0,
    val initial: Boolean,
    val boss: Boolean,
    val scriptClass: Int,
    val influencesClear: Boolean = true,
    val settlesOnDefeat: Boolean = false,
    val maxSelfDamage: Int = 0,
)

/**
 * 单张怪物关卡地图的脚本元数据，从 `.bin` 尾部的 `<mapscriptoption>` 与 `<StageClearScript>` 解析。
 */
data class MonsterStageSpec(
    val mapName: String,
    val playTimeMs: Long = DEFAULT_MONSTER_PLAY_TIME_MS,
    val gameEndWaitMs: Long = DEFAULT_MONSTER_END_WAIT_MS,
    /** 按紧凑 [MonsterTemplate.id] 索引的全部模板（初始怪位于 `0 until initialCount`）。 */
    val monsters: Map<Int, MonsterTemplate>,
) {
    /** 开局即存在于棋盘上的初始怪数量（客户端分配网络对象 ID `0 until initialCount`）。 */
    val initialCount: Int = monsters.values.count { it.initial }

    /** 本关是否存在计入通关条件的 Boss（Boss 关仅以 Boss 为通关目标，召唤小怪不阻塞通关）。 */
    val hasBossObjective: Boolean = monsters.values.any { it.boss && it.influencesClear }

    /** 开局时的初始通关目标对象 ID 集合。 */
    val objectives: Set<Int> = monsters.values
        .filter { it.initial && it.influencesClear && (!hasBossObjective || it.boss) }
        .map { it.id }
        .toSet()

    /** 按 `C136` 24B init 块里的 `scriptClass` 查找刷怪模板。 */
    fun findByScriptClass(scriptClass: Int): MonsterTemplate? =
        monsters.values.firstOrNull { it.scriptClass == scriptClass }

    companion object {
        const val DEFAULT_MONSTER_PLAY_TIME_MS = 120_000L
        const val DEFAULT_MONSTER_END_WAIT_MS = 5_000L

        private val STAGE_CLEAR_MARKER = "<StageClearScript".toByteArray(Charsets.US_ASCII)
        private val MAP_OPTION_MARKER = "<mapscriptoption".toByteArray(Charsets.US_ASCII)

        private val XML_COMMENT_RE = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val MAP_OPTION_RE = Regex("<mapscriptoption\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val SCRIPT_OBJECT_RE = Regex(
            "<ScriptObject\\b[^>]*>(.*?)</ScriptObject>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val INIT_INFO_RE = Regex("<InitInfo\\b([^>]*)>", RegexOption.IGNORE_CASE)
        // 支持双引号与单引号属性（如 MonTurtle08/13/16 的 NoInfluenceGameEnd='1'）
        private val XML_ATTR_RE = Regex("""([A-Za-z0-9_]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val LIFE_MAX_RE = Regex("""max\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val LIFE_ANGER_RE = Regex("""anger\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val SELF_DIE_RE = Regex("""Type\s*=\s*["']selfdie["']""", RegexOption.IGNORE_CASE)
        private val SELF_DAMAGE_RE = Regex("""selfDamage\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)

        /** 三关一组的怪物主题命名规则（如 `MonPen01..30`、`Monster01..39`、`MonSeal01..33`、`MonTurtle01..27`、`MonRobot01..27`、`MonGolem01..27`）。 */
        private val MONSTER_SERIES_RE = Regex("""^(MonPen|Monster|MonSeal|MonTurtle|MonRobot|MonGolem)(\d{2})$""")

        /**
         * 推导同主题三关组内的下一小关名（第 1→2、2→3；第 3 关 Boss 关返回 null）。
         * 调用方应再校验目标地图在 [MapCatalog] 中实际存在且包含 [MonsterStageSpec]。
         */
        fun nextBoardNameOf(mapName: String): String? {
            val match = MONSTER_SERIES_RE.matchEntire(mapName) ?: return null
            val prefix = match.groupValues[1]
            val num = match.groupValues[2].toIntOrNull() ?: return null
            if (num <= 0 || num % 3 == 0) return null
            return prefix + (num + 1).toString().padStart(2, '0')
        }

        /**
         * 将怪物主题三关组内的任意小关归位到第 1 小关（例如 `MonPen02`/`MonPen03` → `MonPen01`）。
         * 非三关组格式返回 null。
         */
        fun firstBoardNameOf(mapName: String): String? {
            val match = MONSTER_SERIES_RE.matchEntire(mapName) ?: return null
            val prefix = match.groupValues[1]
            val num = match.groupValues[2].toIntOrNull() ?: return null
            if (num <= 0) return null
            val firstNum = num - (num - 1) % 3
            return prefix + firstNum.toString().padStart(2, '0')
        }

        /**
         * 从 `.bin` 原始字节中解析 `<mapscriptoption>` 与 `<StageClearScript>`。
         * 不含 `<StageClearScript>` 或不含任何 `<ScriptObject>/<InitInfo>` 时返回 null。
         */
        fun parseFromMapBin(mapName: String, data: ByteArray): MonsterStageSpec? {
            val stageIdx = indexOfBytes(data, STAGE_CLEAR_MARKER)
            if (stageIdx < 0) return null

            val optIdx = indexOfBytes(data, MAP_OPTION_MARKER)
            val start = if (optIdx in 0 until stageIdx) optIdx else stageIdx
            val xml = XML_COMMENT_RE.replace(
                String(data, start, data.size - start, Charsets.ISO_8859_1),
                "",
            )

            var playTimeMs = DEFAULT_MONSTER_PLAY_TIME_MS
            var gameEndWaitMs = DEFAULT_MONSTER_END_WAIT_MS
            MAP_OPTION_RE.find(xml)?.let { match ->
                val opts = parseAttributes(match.groupValues[1])
                opts["playtime"]?.toLongOrNull()?.takeIf { it > 0L }?.let { playTimeMs = it }
                opts["gameendwaittime"]?.toLongOrNull()?.takeIf { it > 0L }?.let { gameEndWaitMs = it }
            }

            data class RawMonster(
                val type: String,
                val hp: Int,
                val angerHp: Int,
                val initial: Boolean,
                val boss: Boolean,
                val explicitClass: Int?,
                val influencesClear: Boolean,
                val settlesOnDefeat: Boolean,
                val maxSelfDamage: Int,
            )

            val rawList = mutableListOf<RawMonster>()
            for (objMatch in SCRIPT_OBJECT_RE.findAll(xml)) {
                val body = objMatch.groupValues[1]
                val initMatch = INIT_INFO_RE.find(body) ?: continue
                val attrs = parseAttributes(initMatch.groupValues[1])
                val type = attrs["type"]?.trim().orEmpty()
                if (type.isEmpty()) continue

                val life = attrs["life"].orEmpty()
                val hp = (LIFE_MAX_RE.find(life)?.groupValues?.get(1)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val angerHp = (LIFE_ANGER_RE.find(life)?.groupValues?.get(1)?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val initial = parseXmlBool(attrs["fromstart"])
                val boss = parseXmlBool(attrs["boss"])
                val explicitClass = attrs["class"]?.trim()?.toIntOrNull()
                val influencesClear = !parseXmlBool(attrs["noinfluencegameend"])
                val hasSelfDie = SELF_DIE_RE.containsMatchIn(body)
                val settlesOnDefeat = type.equals("starFishMon", ignoreCase = true) && !initial && !hasSelfDie
                val maxSelfDamage = SELF_DAMAGE_RE.findAll(body)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .maxOrNull() ?: 0

                rawList += RawMonster(
                    type = type,
                    hp = hp,
                    angerHp = angerHp,
                    initial = initial,
                    boss = boss,
                    explicitClass = explicitClass,
                    influencesClear = influencesClear,
                    settlesOnDefeat = settlesOnDefeat,
                    maxSelfDamage = maxSelfDamage,
                )
            }
            if (rawList.isEmpty()) return null

            // 客户端只给 fromStart="true" 的初始怪分配连续网络 ID 0..K-1，跳过休眠脚本模板；
            // 稳定分区保证初始怪按 XML 原顺序密集排在前面，与客户端 CNetObjectMan 一致。
            val ordered = rawList.filter { it.initial } + rawList.filterNot { it.initial }
            val monsters = LinkedHashMap<Int, MonsterTemplate>(ordered.size)
            ordered.forEachIndexed { id, raw ->
                monsters[id] = MonsterTemplate(
                    id = id,
                    type = raw.type,
                    hp = raw.hp,
                    angerHp = raw.angerHp,
                    initial = raw.initial,
                    boss = raw.boss,
                    scriptClass = raw.explicitClass ?: id,
                    influencesClear = raw.influencesClear,
                    settlesOnDefeat = raw.settlesOnDefeat,
                    maxSelfDamage = raw.maxSelfDamage,
                )
            }

            return MonsterStageSpec(
                mapName = mapName,
                playTimeMs = playTimeMs,
                gameEndWaitMs = gameEndWaitMs,
                monsters = monsters,
            )
        }

        private fun parseAttributes(fragment: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (m in XML_ATTR_RE.findAll(fragment)) {
                val key = m.groupValues[1].lowercase()
                val value = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
                out[key] = value
            }
            return out
        }

        private fun parseXmlBool(raw: String?): Boolean {
            if (raw == null) return false
            val v = raw.trim().lowercase()
            return v == "true" || v == "1"
        }

        private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            val last = haystack.size - needle.size
            outer@ for (i in 0..last) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
