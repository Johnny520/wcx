package h.Hchat.hooks.items.conversationtabs

/** SQL is compiled on selection, never while binding or scrolling conversation rows. */
internal class ConversationTabFilter(val tab: ConversationTab) {
    val active = tab.kind != ConversationTabKind.ALL
    private val containers = setOf("officialaccounts", "service_officialaccounts", "photoaccounts",
        "message_fold", "appbrand_notify_message", "conversationboxservice")
    private val system = containers + setOf("fmessage", "tmessage", "qqmail", "weixin",
        "floatbottle", "medianote", "newsapp", "masssend", "feedsapp", "blogapp")
    private fun literal(value: String) = "'" + value.replace("'", "''") + "'"
    private fun inList(values: Set<String>) = values.sorted().joinToString(",", transform = ::literal)

    private fun predicateFor(qualifier: String): String {
        val user = "$qualifier.username"
        val group = "($user LIKE '%@chatroom' OR $user LIKE '%@im.chatroom')"
        val official = "($user GLOB 'gh_*' OR EXISTS (SELECT 1 FROM rcontact AS hchat_tab_contact " +
            "WHERE hchat_tab_contact.username=$user AND hchat_tab_contact.verifyFlag!=0))"
        return when (tab.kind) {
            ConversationTabKind.ALL -> "1"
            ConversationTabKind.UNREAD -> "($qualifier.unReadCount>0 OR $qualifier.unReadMuteCount>0)"
            ConversationTabKind.GROUP -> group
            ConversationTabKind.PRIVATE -> "NOT $group AND NOT $official AND $user NOT IN (${inList(system)})"
            ConversationTabKind.OFFICIAL -> official
            ConversationTabKind.CUSTOM -> if (tab.conversationIds.isEmpty()) "0" else
                "$user IN (${inList(tab.conversationIds)})"
        }.let { if (!active) it else "($it) AND $user NOT GLOB 'wxid_hchat_group_*' " +
            "AND $user NOT IN (${inList(containers)})" }
    }

    val predicate: String = predicateFor("rconversation")

    fun accepts(username: String, unread: Int, mutedUnread: Int, verifyFlag: Int): Boolean {
        if (!active) return true
        if (username.startsWith("wxid_hchat_group_") || username in containers) return false
        val isGroup = username.endsWith("@chatroom") || username.endsWith("@im.chatroom")
        val isOfficial = username.startsWith("gh_") || verifyFlag != 0
        return when (tab.kind) {
            ConversationTabKind.ALL -> true
            ConversationTabKind.UNREAD -> unread > 0 || mutedUnread > 0
            ConversationTabKind.GROUP -> isGroup
            ConversationTabKind.PRIVATE -> !isGroup && !isOfficial && username !in system
            ConversationTabKind.OFFICIAL -> isOfficial
            ConversationTabKind.CUSTOM -> username in tab.conversationIds
        }
    }

    /** Folded conversations are projected as leaves; stored parentRef is never modified. */
    private fun parent(reference: String): String =
        "(CASE WHEN $reference IN ('officialaccounts','service_officialaccounts','photoaccounts','message_fold') " +
            "OR $reference GLOB 'wxid_hchat_group_*' OR $reference GLOB 'hchat_conv_group:*' " +
            "THEN NULL ELSE $reference END)"

    fun rootWhere(original: String): String = if (!active) original else {
        val qualifier = expressionQualifier(original) ?: "rconversation"
        "(${expandParents(original, qualifier)}) AND (${predicateFor(qualifier)})"
    }

    /** Rewrites only a single native rconversation SELECT, preserving flags, bind args and LIMIT. */
    fun query(sql: String): String {
        if (!active) return sql
        val tokens = tokens(sql)
        val top = tokens.filter { it.depth == 0 }
        if (top.firstOrNull()?.word != "SELECT" || top.any { it.word in setOf("UNION", "INTERSECT", "EXCEPT") }) return sql
        val from = top.indexOfFirst { it.word == "FROM" }
        val qualifier = fromQualifier(sql, top, from) ?: return sql
        val where = top.firstOrNull { it.word == "WHERE" && it.start > top[from].end }
        val tail = top.firstOrNull {
            it.start > (where?.end ?: top[from].end) &&
                it.word in setOf("ORDER", "LIMIT", "GROUP", "HAVING", "OFFSET", "WINDOW")
        }?.start ?: (top.lastOrNull()?.end ?: statementEnd(sql))
        if (where != null) {
            if (tail <= where.end) return sql
            val original = sql.substring(where.end, tail).trim()
            if (original.isEmpty()) return sql
            return sql.substring(0, where.end) +
                " (${expandParents(original, qualifier)}) AND (${predicateFor(qualifier)}) " + sql.substring(tail)
        }
        return sql.substring(0, tail) + " WHERE (${predicateFor(qualifier)}) " + sql.substring(tail)
    }

    private fun expandParents(sql: String, qualifier: String? = null): String {
        val words = tokens(sql)
        val result = StringBuilder(sql)
        words.indices.reversed().forEach { index ->
            val token = words[index]
            if (token.word != "PARENTREF") return@forEach
            var start = token.start
            if (index >= 2 && words[index - 1].word == ".") {
                if (qualifier == null || !words[index - 2].word.equals(qualifier, ignoreCase = true)) return@forEach
                start = words[index - 2].start
            }
            val reference = sql.substring(start, token.end)
            result.replace(start, token.end, parent(reference))
        }
        return result.toString()
    }

    private fun expressionQualifier(sql: String): String? {
        val words = tokens(sql)
        return words.indices.firstNotNullOfOrNull { index ->
            if (words[index].word != "PARENTREF") return@firstNotNullOfOrNull null
            if (index >= 2 && words[index - 1].word == ".") words[index - 2].word else null
        }
    }

    private fun fromQualifier(sql: String, top: List<Token>, from: Int): String? {
        val table = top.getOrNull(from + 1) ?: return null
        if (table.word != "RCONVERSATION") return null
        val alias = top.getOrNull(from + 2)
        if (alias == null || alias.word in SQL_KEYWORDS || sql.substring(table.end, alias.start).contains(',')) {
            return "rconversation"
        }
        if (alias.word == "AS") {
            val value = top.getOrNull(from + 3) ?: return null
            return value.word.takeIf { it !in SQL_KEYWORDS }
        }
        return alias.word
    }

    private fun statementEnd(sql: String): Int {
        var end = sql.length
        while (end > 0 && sql[end - 1].isWhitespace()) end--
        if (end > 0 && sql[end - 1] == ';') end--
        while (end > 0 && sql[end - 1].isWhitespace()) end--
        return end
    }

    private companion object {
        val SQL_KEYWORDS = setOf(
            "WHERE", "ORDER", "LIMIT", "GROUP", "HAVING", "OFFSET", "WINDOW", "JOIN",
            "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "ON", "UNION", "INTERSECT",
            "EXCEPT", "RETURNING", "FOR"
        )
    }

    private data class Token(val word: String, val start: Int, val end: Int, val depth: Int)

    /** Keep literals/comments opaque so names containing SQL words cannot change query structure. */
    private fun tokens(sql: String): List<Token> {
        val result = ArrayList<Token>()
        var i = 0
        var depth = 0
        while (i < sql.length) {
            val start = i
            val c = sql[i++]
            when {
                c == '\'' -> {
                    while (i < sql.length) {
                        if (sql[i++] == '\'') {
                            if (i < sql.length && sql[i] == '\'') i++ else break
                        }
                    }
                }
                c == '-' && i < sql.length && sql[i] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i < sql.length && sql[i] == '*' -> {
                    i = (sql.indexOf("*/", i + 1).takeIf { it >= 0 }?.plus(2) ?: sql.length)
                }
                c == '(' -> depth++
                c == ')' -> depth--
                c == '.' -> result.add(Token(".", start, i, depth))
                c == '`' || c == '"' || c == '[' -> {
                    val endQuote = if (c == '[') ']' else c
                    val end = sql.indexOf(endQuote, i).takeIf { it >= 0 } ?: return emptyList()
                    result.add(Token(sql.substring(i, end).uppercase(), start, end + 1, depth))
                    i = end + 1
                }
                c.isLetter() || c == '_' -> {
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    result.add(Token(sql.substring(start, i).uppercase(), start, i, depth))
                }
            }
        }
        return result
    }
}
