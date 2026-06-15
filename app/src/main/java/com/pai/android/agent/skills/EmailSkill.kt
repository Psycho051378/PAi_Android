package com.pai.android.agent.skills

import android.content.Context
import com.pai.android.agent.Intent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Properties
import java.util.Date
import java.util.Calendar
import java.text.SimpleDateFormat
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.*
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class EmailSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : Skill {

    companion object {
        @Volatile var enabled: Boolean = true
        const val MAX_EMAILS = 10
    }

    override val name: String = "email"
    override val description: String = "Check, read, send emails via IMAP/SMTP"

    // Email accounts stored in shared prefs (simplified — use EncryptedSharedPreferences in production)
    private val prefs by lazy {
        context.getSharedPreferences("email_accounts", Context.MODE_PRIVATE)
    }

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == Intent.TOOL_OPERATION && params["command"]?.toString()?.startsWith("email_") == true) return true
        val lower = query.lowercase()
        return lower.contains("почт") || lower.contains("email") || lower.contains("mail") ||
               lower.contains("письм") || lower.contains("ящик")
    }

    private fun getAccounts(): List<EmailAccount> {
        val count = prefs.getInt("account_count", 0)
        return (0 until count).mapNotNull { i ->
            val json = prefs.getString("account_$i", null) ?: return@mapNotNull null
            EmailAccount.fromJson(json)
        }
    }

    private fun saveAccounts(accounts: List<EmailAccount>) {
        prefs.edit().apply {
            putInt("account_count", accounts.size)
            accounts.forEachIndexed { i, acc -> putString("account_$i", acc.toJson()) }
            apply()
        }
    }

    fun addAccount(account: EmailAccount) {
        val accounts = getAccounts().toMutableList()
        accounts.removeAll { it.id == account.id }
        accounts.add(account)
        saveAccounts(accounts)
    }

    fun removeAccount(id: String) {
        val accounts = getAccounts().toMutableList()
        accounts.removeAll { it.id == id }
        saveAccounts(accounts)
    }

    fun getAccount(id: String): EmailAccount? = getAccounts().find { it.id == id }

    fun listAccounts(): List<EmailAccount> = getAccounts()

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        try {
            val command = params["command"]?.toString() ?: "check"
            val accountId = params["account"]?.toString() ?: ""
            // Always use the first configured account (ignore wrong account names)
            val allAccounts = getAccounts()
            val account = allAccounts.firstOrNull()

            if (account == null) {
                return@withContext SkillResult.Error(
                    message = "Email not configured. Add an account: Skills > Email Client > Settings."
                )
            }

            when (command) {
                "check", "email_check" -> checkEmail(account, params)
                "list", "email_list" -> listEmails(account, params)
                "read", "email_read" -> readEmail(account, params)
                "send", "email_send" -> sendEmail(account, params)
                "search", "email_search" -> listEmails(account, params)
                else -> SkillResult.Error(message = "Unknown email command: $command")
            }
        } catch (e: Exception) {
            SkillResult.Error(message = "Email error: ${e.message ?: "Unknown error"}")
        }
    }

    private fun connectStore(account: EmailAccount): Store {
        val props = Properties().apply {
            put("mail.imap.host", account.imapServer)
            put("mail.imap.port", account.imapPort.toString())
            put("mail.imap.ssl.enable", account.useSSL.toString())
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "15000")
        }
        val session = Session.getInstance(props, null)
        val store = session.getStore("imaps")
        store.connect(account.imapServer, account.username, account.password)
        return store
    }

    private fun checkEmail(account: EmailAccount, params: Map<String, Any>): SkillResult {
        val store = connectStore(account)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            // Get unread messages
            val unseen = FlagTerm(Flags(Flags.Flag.SEEN), false)
            val unreadMessages = inbox.search(unseen)
            val totalCount = inbox.messageCount
            val unreadCount = unreadMessages.size

            // Get recent messages (last 5)
            val recentStart = maxOf(1, totalCount - 49)
            val recentMessages = inbox.getMessages(recentStart, totalCount)

            val sb = StringBuilder()
            sb.appendLine("📬 **${account.displayName}** — проверка почты")
            sb.appendLine("┌────────────────────────────")
            sb.appendLine("│ Всего писем: $totalCount")
            sb.appendLine("│ Непрочитано: $unreadCount")
            sb.appendLine("└────────────────────────────")
            sb.appendLine()

            if (unreadCount > 0) {
                sb.appendLine("**📩 Непрочитанные письма:**")
                unreadMessages.take(10).forEachIndexed { idx, msg ->
                    try {
                        val from = InternetAddress.toString(msg.from); val fromRaw = try { (msg.from?.firstOrNull() as? javax.mail.internet.InternetAddress)?.address ?: "?" } catch(_:Exception) { "?" }
                        val subject = msg.subject ?: "(без темы)"
                        sb.appendLine("${idx + 1}. ✉️ [UID=" + (try { (inbox as? javax.mail.UIDFolder)?.getUID(msg) ?: msg.messageNumber } catch(_:Exception) { msg.messageNumber }) + "] [$from] addr=$fromRaw $subject")
                    } catch (_: Exception) { }
                }
                sb.appendLine()
            }

            sb.appendLine("**📋 Последние письма:**")
            recentMessages.takeLast(20).forEachIndexed { idx, msg ->
                try {
                    val from = InternetAddress.toString(msg.from)
                    val subject = msg.subject ?: "(без темы)"
                    val date = msg.receivedDate ?: msg.sentDate
                    val dateStr = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(date)
                    sb.appendLine("${idx + 1}. 📧 [UID=" + (try { (inbox as? javax.mail.UIDFolder)?.getUID(msg) ?: msg.messageNumber } catch(_:Exception) { msg.messageNumber }) + "] [$from] «$subject» ($dateStr)")
                } catch (_: Exception) { }
            }

            inbox.close(false)
            return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
        } finally {
            try { store.close() } catch (_: Exception) { }
        }
    }

    private fun listEmails(account: EmailAccount, params: Map<String, Any>): SkillResult {
        val store = connectStore(account)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val searchQuery = params["query"]?.toString()?.trim() ?: ""
            val rawLimit = params["limit"]?.toString()?.toIntOrNull() ?: if (searchQuery.isNotBlank()) 50 else MAX_EMAILS
            val limit = rawLimit.coerceIn(1, 100)

            // Строим IMAP SEARCH термин (сервер делает поиск, не перебираем вручную)
            val searchTerm = buildSearchTerm(searchQuery)
            
            val matchedMessages = if (searchTerm != null) {
                inbox.search(searchTerm)
            } else {
                // Без поиска — последние N сообщений
                val count = inbox.messageCount
                val start = maxOf(1, count - limit * 2 + 1) // небольшой запас
                inbox.getMessages(start, count)
            }
            
            val resultMessages = if (searchQuery.isNotBlank()) matchedMessages.toList() else matchedMessages.toList().takeLast(limit)
            Log.d("EMAIL_SEARCH", "IMAP search: q='$searchQuery', found=${matchedMessages.size}, showing=${resultMessages.size}")

            val sb = StringBuilder()
            if (searchQuery.isNotBlank()) {
                sb.appendLine("📧 **${account.displayName}** — поиск по запросу \"$searchQuery\":")
            } else {
                sb.appendLine("📧 **${account.displayName}** — последние письма:")
            }
            sb.appendLine()

            for (msg in resultMessages) {
                try {
                    val from = InternetAddress.toString(msg.from)
                    val subject = msg.subject ?: "(без темы)"
                    val date = msg.receivedDate ?: msg.sentDate
                    val dateStr = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(date)
                    val isUnread = !msg.flags.contains(Flags.Flag.SEEN)
                    val uid = try { (inbox as? javax.mail.UIDFolder)?.getUID(msg) ?: msg.messageNumber.toLong() } catch (_: Exception) { msg.messageNumber.toLong() }
                    
                    sb.appendLine("${if (isUnread) "📩" else "📧"} [UID=$uid] | $from | «$subject» | $dateStr")
                } catch (_: Exception) { }
            }

            if (resultMessages.isEmpty()) sb.appendLine("Писем по запросу не найдено.")

            inbox.close(false)
            return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
        } finally {
            try { store.close() } catch (_: Exception) { }
        }
    }

    /**
     * Строит IMAP SearchTerm из запроса пользователя.
     * Поддерживает: отправитель (email/имя), тема (ключевые слова), дата (DD.MM.YYYY),
     * и комбинации: "от кого-то про что-то"
     */
    private fun buildSearchTerm(query: String): SearchTerm? {
        if (query.isBlank()) return null
        
        val q = query.lowercase().trim()
        val terms = mutableListOf<SearchTerm>()
        val dateFormat1 = SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        val dateFormat2 = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        
        // Ищем дату в запросе
        val dateRegex = Regex("""(\d{2}\.\d{2}\.\d{4}|\d{4}-\d{2}-\d{2})""")
        val dateMatch = dateRegex.find(q)
        if (dateMatch != null) {
            try {
                val rawDate = dateMatch.value
                val date = if (rawDate.contains(".")) dateFormat1.parse(rawDate) else dateFormat2.parse(rawDate)
                if (date != null) {
                    // Ищем письма ЗА эту дату (с 00:00 до 23:59)
                    val cal = Calendar.getInstance().apply { time = date }
                    val dayStart = cal.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.time
                    val dayEnd = cal.apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }.time
                    terms.add(AndTerm(
                        ReceivedDateTerm(ComparisonTerm.GE, dayStart),
                        ReceivedDateTerm(ComparisonTerm.LE, dayEnd)
                    ))
                }
            } catch (_: Exception) { }
        }
        
        // Убираем дату из запроса для поиска по тексту
        val textQuery = q.replace(dateRegex, "").trim()
            .replace(Regex("[,\\.!?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        if (textQuery.isNotBlank()) {
            val words = textQuery.split(" ").filter { it.length > 1 }
            
            // Проверяем, похоже ли слово на email (содержит @)
            val emailWords = words.filter { it.contains("@") }
            val subjectWords = words.filter { !it.contains("@") }
            
            // Поиск по отправителю (email)
            if (emailWords.isNotEmpty()) {
                val fromTerms = emailWords.map { FromStringTerm(it) }
                terms.addAll(fromTerms)
            }
            
            // Поиск по теме (ключевые слова)
            if (subjectWords.isNotEmpty()) {
                val subjectTerms = subjectWords.map { SubjectTerm(it) }
                if (subjectTerms.size == 1) {
                    terms.add(subjectTerms.first())
                } else {
                    terms.add(OrTerm(subjectTerms.toTypedArray()))
                }
            }
        }
        
        return when {
            terms.isEmpty() -> null
            terms.size == 1 -> terms.first()
            else -> AndTerm(terms.toTypedArray())
        }
    }

    private fun readEmail(account: EmailAccount, params: Map<String, Any>): SkillResult {
        val uid = params["uid"]?.toString()?.toLongOrNull()
            ?: params["id"]?.toString()?.toLongOrNull()
            ?: return SkillResult.Error(message = "Укажите номер письма (uid)")

        val store = connectStore(account)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val msg: javax.mail.Message = try {
                (inbox as? javax.mail.UIDFolder)?.getMessageByUID(uid)
            } catch (_: Exception) { null } ?: run {
                val pos = uid.toInt()
                if (pos < 1 || pos > inbox.messageCount) {
                    return@readEmail SkillResult.Error(message = "Письмо #$uid не найдено")
                }
                inbox.getMessage(pos)
            }
            val from = InternetAddress.toString(msg.from)
            val to = InternetAddress.toString(msg.allRecipients)
            val subject = msg.subject ?: "(без темы)"
            val date = msg.receivedDate ?: msg.sentDate
            val dateStr = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(date)

            // Get content
            val content = getTextContent(msg)

            val sb = StringBuilder()
            sb.appendLine("📄 **Письмо [UID=$uid]**")
            sb.appendLine("📅 $dateStr")
            sb.appendLine("📤 От: $from")
            sb.appendLine("📥 Кому: $to")
            sb.appendLine("📌 Тема: $subject")
            sb.appendLine()
            sb.appendLine("```")
            sb.appendLine(content.take(5000))
            sb.appendLine("```")

            inbox.close(false)
            return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
        } finally {
            try { store.close() } catch (_: Exception) { }
        }
    }

    private fun sendEmail(account: EmailAccount, params: Map<String, Any>): SkillResult {
        val to = params["to"]?.toString() ?: return SkillResult.Error(message = "Укажите получателя (to)")
        val subject = params["subject"]?.toString() ?: ""
        val body = params["body"]?.toString() ?: params["content"]?.toString() ?: ""
        
        // If forward_uid is specified, read the original email and use its content as body
        val forwardUid = params["forward_uid"]?.toString()?.toLongOrNull() ?: params["fwd_uid"]?.toString()?.toLongOrNull()
        val finalBody = if (forwardUid != null && body.isBlank()) {
            try {
                val fwdStore = connectStore(account)
                try {
                    val fwdInbox = fwdStore.getFolder("INBOX")
                    fwdInbox.open(Folder.READ_ONLY)
                    val originalMsg = (fwdInbox as? javax.mail.UIDFolder)?.getMessageByUID(forwardUid)
                    if (originalMsg != null) {
                        val fwdContent = getTextContent(originalMsg)
                        val fwdFrom = InternetAddress.toString(originalMsg.from)
                        val fwdSubject = originalMsg.subject ?: "(без темы)"
                        val fwdDate = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(originalMsg.receivedDate ?: originalMsg.sentDate)
                        "---------- Forwarded message ----------\nFrom: $fwdFrom\nDate: $fwdDate\nSubject: $fwdSubject\n\n$fwdContent"
                    } else body
                } finally { try { fwdStore.close() } catch (_: Exception) { } }
            } catch (_: Exception) { body }
        } else body

        val props = Properties().apply {
            put("mail.smtp.host", account.smtpServer)
            put("mail.smtp.port", account.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", account.useSSL.toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.socketFactory.port", account.smtpPort.toString())
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.ssl.checkserveridentity", "false")
            put("mail.smtp.ssl.trust", "*")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(account.username, account.password)
            }
        })

        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(account.username))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            sentDate = java.util.Date()
            
            // Проверяем, есть ли вложения
            val attachmentPaths = params["file_path"]?.toString()
                ?: params["attachment_paths"]?.toString()
                ?: params["attachments"]?.toString()
                ?: ""
            val paths = attachmentPaths.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
            
            if (paths.isEmpty()) {
                // Без вложений — простой текст
                setText(body, "UTF-8")
            } else {
                // С вложениями — multipart/mixed
                val multipart = MimeMultipart("mixed")
                
                // Текстовый body part
                val bodyPart = MimeBodyPart()
                bodyPart.setText(body, "UTF-8")
                multipart.addBodyPart(bodyPart)
                
                // Файловые вложения
                for (filePath in paths) {
                    try {
                        val resolved = resolveFilePath(filePath)
                        if (resolved != null) {
                            val attachPart = MimeBodyPart()
                            attachPart.attachFile(resolved)
                            attachPart.fileName = resolved.name
                            multipart.addBodyPart(attachPart)
                            Log.d("EMAIL_ATTACH", "Attached: ${resolved.absolutePath}")
                        } else {
                            Log.w("EMAIL_ATTACH", "File not found: $filePath")
                        }
                    } catch (e: Exception) {
                        Log.e("EMAIL_ATTACH", "Failed to attach $filePath: ${e.message}")
                    }
                }
                
                setContent(multipart)
            }
        }

        try {
            Transport.send(msg)
            Log.d("EMAIL_SEND", "Success: to=$to subject=$subject")
        } catch (e: Exception) {
            val errMsg = "SMTP error: ${e.message ?: "Unknown"} (${e.javaClass.simpleName})"
            Log.e("EMAIL_SEND", errMsg)
            return SkillResult.Error(message = errMsg)
        }

        val attachInfo = params["file_path"]?.toString() ?: params["attachment_paths"]?.toString() ?: params["attachments"]?.toString() ?: ""
        val attachMsg = if (attachInfo.isNotBlank()) "\n📎 Вложение: $attachInfo" else ""
        return SkillResult.Success(
            message = "✅ Письмо отправлено на $to\n📌 Тема: $subject$attachMsg",
            responseType = ResponseType.TEXT
        )
    }

    /**
     * Ищет файл в нескольких возможных расположениях.
     * 1. Прямой путь (абсолютный или относительный)
     * 2. filesDir/filePath
     * 3. externalFilesDir/workspace/filePath
     * 4. filesDir/workspace/filePath
     * 5. Рекурсивный поиск по имени в workspace
     */
    private fun resolveFilePath(filePath: String): java.io.File? {
        // 1. Как есть
        var f = java.io.File(filePath)
        if (f.exists() && f.isFile) return f
        
        // 2. filesDir + filePath
        f = java.io.File(context.filesDir, filePath)
        if (f.exists() && f.isFile) return f
        
        // 3. externalFilesDir + /workspace/ + filePath
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            f = java.io.File(extDir, "workspace/$filePath")
            if (f.exists() && f.isFile) return f
            
            // 4. externalFilesDir + filePath
            f = java.io.File(extDir, filePath)
            if (f.exists() && f.isFile) return f
        }
        
        // 5. filesDir + /workspace/ + filePath
        f = java.io.File(context.filesDir, "workspace/$filePath")
        if (f.exists() && f.isFile) return f
        
        // 6. Рекурсивный поиск по имени файла в workspace
        val workspaceDirs = listOfNotNull(
            java.io.File(context.filesDir, "workspace"),
            extDir?.let { java.io.File(it, "workspace") }
        )
        for (dir in workspaceDirs) {
            if (dir.exists() && dir.isDirectory) {
                val found = dir.walkTopDown().find { it.isFile && it.name == filePath }
                if (found != null) return found
            }
        }
        
        return null
    }

    private fun getTextContent(msg: Message): String {
        return try {
            val raw = getRawContent(msg)
            // Strip HTML tags for readability
            val noHtml = raw.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("&[a-z]+;"), " ")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("\\s+"), " ")
                .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match -> match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value }
            .replace(Regex("[\u200B-\u200F\u2028-\u202F\u2800\u00A0\u2000-\u200A\u205F\u3000]")) { " " }
            .trim()
            noHtml.ifBlank { "(содержимое только в формате HTML)" }
        } catch (_: Exception) { "(не удалось прочитать содержимое)" }
    }

    private fun getRawContent(msg: Message): String {
        val content = msg.content
        return when (content) {
            is String -> content
            is Multipart -> {
                val sb = StringBuilder()
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    try {
                        sb.append(part.content.toString())
                    } catch (_: Exception) { }
                }
                sb.toString()
            }
            else -> content.toString()
        }
    }
}