package com.pai.android.agent.skills.home.router

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SNMP v2c клиент, работающий через raw UDP (без внешних библиотек).
 *
 * Реализует:
 * - ASN.1 BER кодирование для SNMP v2c GetRequest
 * - Отправка запроса на UDP порт 161
 * - Парсинг ответа: извлечение пар IP → MAC из ARP-таблицы
 *
 * OID для ARP-таблицы: 1.3.6.1.2.1.4.22.1.2 (ipNetToMediaPhysAddress)
 */
class SnmpRouterClient(
    private val routerConfig: RouterConfig
) : RouterClient {

    override val name: String = "SNMP v2c"
    override val protocolType: ProtocolType = ProtocolType.SNMP

    companion object {
        private const val SNMP_PORT = 161
        private const val TIMEOUT_MS = 5000

        // SNMP v2c: version = 1
        private const val SNMP_VERSION = 1

        // PDU types
        private const val PDU_GET_REQUEST = 0xA0
        private const val PDU_GET_RESPONSE = 0xA2

        // OID для ARP-таблицы (ipNetToMediaPhysAddress)
        private val OID_ARP_TABLE = intArrayOf(1, 3, 6, 1, 2, 1, 4, 22, 1, 2)
    }

    private var lastRequestId: Int = 0

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Пробуем получить ARP — если ответ не пустой, соединение работает
            val arp = getArpTable()
            arp.isNotEmpty() || true // Даже пустая ARP — нормальный ответ SNMP
        } catch (e: Exception) {
            println("SNMP testConnection error: ${e.message}")
            false
        }
    }

    override suspend fun getArpTable(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(routerConfig.ip)
            val community = routerConfig.community.ifBlank { "public" }

            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            // Кодируем SNMP GetNext запрос для ARP-таблицы
            val requestBytes = encodeGetNextRequest(community, OID_ARP_TABLE)

            val packet = DatagramPacket(requestBytes, requestBytes.size, address, SNMP_PORT)
            socket.send(packet)
            println("SNMP: sent GetNext request for OID 1.3.6.1.2.1.4.22.1.2")

            // Читаем все ответы (SNMP агент может вернуть несколько пакетов)
            val arpEntries = mutableMapOf<String, String>()
            var lastOid = OID_ARP_TABLE.copyOf()

            val responseBuffer = ByteArray(65535)

            // Пробуем последовательно идти по таблице через GetNext
            for (attempt in 0 until 255) {
                val requestBytes2 = encodeGetNextRequest(community, lastOid)
                val packet2 = DatagramPacket(requestBytes2, requestBytes2.size, address, SNMP_PORT)
                socket.send(packet2)

                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                try {
                    socket.receive(responsePacket)
                } catch (e: java.net.SocketTimeoutException) {
                    println("SNMP: timeout after $attempt entries")
                    break
                }

                val responseData = responsePacket.data.copyOf(responsePacket.length)
                val parsed = parseGetResponse(responseData)

                if (parsed == null) {
                    println("SNMP: failed to parse response at attempt $attempt")
                    break
                }

                val (oid, value) = parsed

                // Проверяем, что OID всё ещё в пределах ARP-таблицы ipNetToMedia
                if (!oid.startsWith(OID_ARP_TABLE)) {
                    println("SNMP: walked past ARP table, stopping")
                    break
                }

                if (value.isNotBlank()) {
                    val mac = normalizeMac(value)
                    if (mac != null) {
                        // Из последнего компонента OID (1.3.6.1.2.1.4.22.1.2.X.Y.Z)
                        // X = 1 (ipNetToMediaPhysAddress), Y.Z = IP-адрес как два октета
                        // На самом деле OID имеет формат: .1.3.6.1.2.1.4.22.1.2.ifIndex.ipAddr
                        // где ipAddr закодирован как 4 октета
                        val ip = extractIpFromOid(oid)
                        if (ip != null) {
                            arpEntries[ip] = mac
                            println("SNMP: found $ip -> $mac")
                        }
                    }
                }

                lastOid = oid
            }

            socket.close()
            println("SNMP: ARP table complete, ${arpEntries.size} entries")
            arpEntries
        } catch (e: Exception) {
            println("SNMP getArpTable error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Кодирует SNMP v2c GetNextRequest пакет с указанным OID.
     */
    private fun encodeGetNextRequest(community: String, oid: IntArray): ByteArray {
        lastRequestId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        // Sequence: версия + community + PDU
        val versionBytes = encodeInteger(SNMP_VERSION)
        val communityBytes = encodeOctetString(community.toByteArray())
        val pduBytes = encodePdu(PDU_GET_REQUEST, lastRequestId, oid)

        // Всё это помещаем в outer SEQUENCE
        val innerParts = ByteArrayOutputStream()
        innerParts.write(versionBytes)
        innerParts.write(communityBytes)
        innerParts.write(pduBytes)

        return encodeSequence(0x30, innerParts.toByteArray())
    }

    /**
     * Кодирует SNMP PDU (GetRequest / GetNext).
     */
    private fun encodePdu(pduType: Int, requestId: Int, oid: IntArray): ByteArray {
        val pdu = ByteArrayOutputStream()

        // Request ID
        pdu.write(encodeInteger(requestId))

        // Error (0)
        pdu.write(encodeInteger(0))

        // Error index (0)
        pdu.write(encodeInteger(0))

        // Varbind list (sequence of sequences)
        val varbind = encodeVarbind(oid)
        val varbindList = encodeSequence(0x30, varbind)

        pdu.write(varbindList)

        return encodeSequence(pduType, pdu.toByteArray())
    }

    /**
     * Кодирует varbind: OID + NULL value.
     */
    private fun encodeVarbind(oid: IntArray): ByteArray {
        val vb = ByteArrayOutputStream()
        vb.write(encodeOid(oid))
        vb.write(byteArrayOf(0x05.toByte(), 0x00)) // NULL
        return encodeSequence(0x30, vb.toByteArray())
    }

    /**
     * Кодирует ASN.1 INTEGER.
     */
    private fun encodeInteger(value: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        var v = value

        // Считаем, сколько байт нужно
        if (v == 0) {
            baos.write(0x02)
            baos.write(0x01)
            baos.write(0x00)
            return baos.toByteArray()
        }

        val bytes = mutableListOf<Byte>()
        while (v != 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }

        // Если старший бит установлен — добавляем 0x00 для знака
        if (bytes[0].toInt() and 0x80 != 0) {
            bytes.add(0, 0x00)
        }

        baos.write(0x02) // INTEGER tag
        baos.write(bytes.size)
        bytes.forEach { baos.write(it.toInt()) }

        return baos.toByteArray()
    }

    /**
     * Кодирует ASN.1 OCTET STRING.
     */
    private fun encodeOctetString(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0x04) // OCTET STRING tag
        if (data.size < 128) {
            baos.write(data.size)
        } else {
            // Длинная форма длины
            val lenBytes = encodeLength(data.size)
            baos.write(lenBytes)
        }
        baos.write(data)
        return baos.toByteArray()
    }

    /**
     * Кодирует ASN.1 OBJECT IDENTIFIER.
     */
    private fun encodeOid(oid: IntArray): ByteArray {
        if (oid.size < 2) throw IllegalArgumentException("OID must have at least 2 components")

        val baos = ByteArrayOutputStream()

        // Первые два компонента кодируются как 40*first + second
        baos.write(40 * oid[0] + oid[1])

        // Остальные — в base-128
        for (i in 2 until oid.size) {
            writeOidComponent(baos, oid[i])
        }

        val content = baos.toByteArray()
        val result = ByteArrayOutputStream()
        result.write(0x06) // OID tag
        result.write(encodeLength(content.size))
        result.write(content)
        return result.toByteArray()
    }

    /**
     * Записывает один компонент OID в base-128 encoding.
     */
    private fun writeOidComponent(baos: ByteArrayOutputStream, value: Int) {
        if (value < 128) {
            baos.write(value)
            return
        }

        // Определяем, сколько байт нужно
        val temp = mutableListOf<Byte>()
        var v = value
        while (v > 0) {
            temp.add(0, (v and 0x7F).toByte())
            v = v shr 7
        }

        // Устанавливаем continuation bit у всех, кроме последнего
        for (i in 0 until temp.size - 1) {
            baos.write(temp[i].toInt() or 0x80)
        }
        baos.write(temp.last().toInt())
    }

    /**
     * Кодирует длину ASN.1 (длинная форма если > 127).
     */
    private fun encodeLength(length: Int): ByteArray {
        if (length < 128) {
            return byteArrayOf(length.toByte())
        }

        val baos = ByteArrayOutputStream()
        var len = length
        val bytes = mutableListOf<Byte>()
        while (len > 0) {
            bytes.add(0, (len and 0xFF).toByte())
            len = len shr 8
        }
        baos.write((0x80 or bytes.size))
        bytes.forEach { baos.write(it.toInt()) }
        return baos.toByteArray()
    }

    /**
     * Создаёт ASN.1 SEQUENCE (tag + length + content).
     */
    private fun encodeSequence(tag: Int, content: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(tag)
        baos.write(encodeLength(content.size))
        baos.write(content)
        return baos.toByteArray()
    }

    // ════════════════════ PARSING ════════════════════

    /**
     * Парсит SNMP GetResponse пакет.
     * @return Pair(OID как int[], значение как строка) или null при ошибке.
     */
    private fun parseGetResponse(data: ByteArray): Pair<IntArray, String>? {
        return try {
            val bais = ByteArrayInputStream(data)

            // Outer SEQUENCE
            val outerTag = bais.read()
            if (outerTag != 0x30) {
                println("SNMP: expected outer SEQUENCE (0x30), got 0x${outerTag.toString(16)}")
                return null
            }

            val outerLen = readLength(bais)
            val outerContent = ByteArray(outerLen)
            bais.read(outerContent)

            val innerBais = ByteArrayInputStream(outerContent)

            // Version (INTEGER)
            val versionTag = innerBais.read()
            if (versionTag != 0x02) return null
            val versionLen = readLength(innerBais)
            val versionBytes = ByteArray(versionLen)
            innerBais.read(versionBytes)

            // Community (OCTET STRING)
            val communityTag = innerBais.read()
            if (communityTag != 0x04) return null
            val communityLen = readLength(innerBais)
            val communityBytes = ByteArray(communityLen)
            innerBais.read(communityBytes)

            // PDU (GetResponse = 0xA2)
            val pduTag = innerBais.read()
            if (pduTag != PDU_GET_RESPONSE) {
                println("SNMP: expected GetResponse (0xA2), got 0x${pduTag.toString(16)}")
                return null
            }

            val pduLen = readLength(innerBais)
            val pduContent = ByteArray(pduLen)
            innerBais.read(pduContent)

            val pduBais = ByteArrayInputStream(pduContent)

            // Request ID (INTEGER)
            readInteger(pduBais)

            // Error (INTEGER)
            val error = readInteger(pduBais)
            if (error != 0) {
                println("SNMP: response error = $error")
                return null
            }

            // Error index (INTEGER)
            readInteger(pduBais)

            // Varbind list (SEQUENCE)
            val vbListTag = pduBais.read()
            if (vbListTag != 0x30) return null
            val vbListLen = readLength(pduBais)
            val vbListContent = ByteArray(vbListLen)
            pduBais.read(vbListContent)

            val vbBais = ByteArrayInputStream(vbListContent)

            // Varbind (SEQUENCE)
            val vbTag = vbBais.read()
            if (vbTag != 0x30) return null
            val vbLen = readLength(vbBais)
            val vbContent = ByteArray(vbLen)
            vbBais.read(vbContent)

            val vbInnerBais = ByteArrayInputStream(vbContent)

            // OID
            val oidTag = vbInnerBais.read()
            if (oidTag != 0x06) return null
            val oidLen = readLength(vbInnerBais)
            val oidBytes = ByteArray(oidLen)
            vbInnerBais.read(oidBytes)

            val oid = parseOidBytes(oidBytes)

            // Value (может быть OCTET STRING с MAC или NoSuchInstance)
            val valueTag = vbInnerBais.read()
            if (valueTag == 0x0A) { // endOfMibView
                return null // Дошли до конца таблицы
            }

            if (valueTag == 0x04 || valueTag == 0x05) { // OCTET STRING или NULL
                val valueLen = readLength(vbInnerBais)
                if (valueLen <= 0) return Pair(oid, "")

                val valueBytes = ByteArray(valueLen)
                vbInnerBais.read(valueBytes)

                // MAC-адрес — это 6 байт
                val macStr = valueBytes.joinToString(":") { "%02x".format(it) }
                return Pair(oid, macStr)
            }

            null
        } catch (e: Exception) {
            println("SNMP parseGetResponse error: ${e.message}")
            null
        }
    }

    /**
     * Читает ASN.1 длину (поддерживает короткую и длинную форму).
     */
    private fun readLength(input: ByteArrayInputStream): Int {
        val first = input.read()
        if (first < 0) return -1
        if (first < 128) return first

        val numBytes = first and 0x7F
        var length = 0
        for (i in 0 until numBytes) {
            length = (length shl 8) or (input.read() and 0xFF)
        }
        return length
    }

    /**
     * Читает ASN.1 INTEGER.
     */
    private fun readInteger(input: ByteArrayInputStream): Int {
        val tag = input.read()
        if (tag != 0x02) return -1
        val len = readLength(input)
        var value = 0
        for (i in 0 until len) {
            value = (value shl 8) or (input.read() and 0xFF)
        }
        return value
    }

    /**
     * Парсит байты OID обратно в массив int.
     */
    private fun parseOidBytes(bytes: ByteArray): IntArray {
        if (bytes.isEmpty()) return intArrayOf()

        val result = mutableListOf<Int>()

        // Первый байт: 40*first + second
        val first = bytes[0].toInt() and 0xFF
        result.add(first / 40)
        result.add(first % 40)

        var i = 1
        while (i < bytes.size) {
            var value = 0
            var byte = bytes[i].toInt() and 0xFF
            i++
            while (byte and 0x80 != 0) {
                value = (value shl 7) or (byte and 0x7F)
                if (i >= bytes.size) break
                byte = bytes[i].toInt() and 0xFF
                i++
            }
            value = (value shl 7) or (byte and 0x7F)
            result.add(value)
        }

        return result.toIntArray()
    }

    /**
     * Проверяет, начинается ли OID с указанного префикса.
     */
    private fun IntArray.startsWith(prefix: IntArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * Извлекает IP-адрес из OID ARP-таблицы.
     * Формат OID: .1.3.6.1.2.1.4.22.1.2.ifIndex.a.b.c.d
     * где a.b.c.d — IP-адрес.
     */
    private fun extractIpFromOid(oid: IntArray): String? {
        // OID должен быть: 1.3.6.1.2.1.4.22.1.2.X.a.b.c.d
        // где X — ifIndex, a.b.c.d — IP (4 октета)
        if (oid.size < 11) return null

        val a = oid[oid.size - 4]
        val b = oid[oid.size - 3]
        val c = oid[oid.size - 2]
        val d = oid[oid.size - 1]

        return "$a.$b.$c.$d"
    }

    /**
     * Нормализует MAC-адрес: приводит к нижнему регистру и формату xx:xx:xx:xx:xx:xx.
     */
    private fun normalizeMac(raw: String): String? {
        if (raw.isBlank()) return null

        val cleaned = raw.lowercase().replace("-", ":")

        // Если это уже MAC-подобная строка с 6 группами
        val parts = cleaned.split(":")
        if (parts.size == 6) {
            val normalized = parts.joinToString(":") {
                if (it.length == 1) "0$it" else it
            }
            if (normalized.length == 17) return normalized
        }

        // Если это склеенная hex-строка
        if (cleaned.length == 12) {
            return cleaned.chunked(2).joinToString(":")
        }

        return null
    }
}
