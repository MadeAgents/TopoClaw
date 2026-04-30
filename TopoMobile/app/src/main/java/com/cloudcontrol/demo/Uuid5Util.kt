package com.cloudcontrol.demo

import java.security.MessageDigest
import java.util.UUID

/**
 * UUID v5 工具类
 * 与 PC 端 uuidv5(imei + '_' + conversationId, namespace) 保持兼容，
 * 用于跨设备同步聊天小助手时生成一致的 thread_id
 */
object Uuid5Util {
    private const val NAMESPACE_CHAT_ASSISTANT = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    /**
     * 生成聊天小助手的 thread_id
     * @param imei 设备 IMEI
     * @return 与 PC 端一致的 UUID 字符串，格式如 "550e8400-e29b-41d4-a716-446655440000"
     */
    fun chatAssistantThreadId(imei: String): String {
        val name = "${imei}_chat_assistant"
        return uuid5(NAMESPACE_CHAT_ASSISTANT, name)
    }

    /**
     * UUID v5: namespace UUID + name 的 SHA-1 哈希
     */
    fun uuid5(namespaceUuid: String, name: String): String {
        val namespace = UUID.fromString(namespaceUuid)
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        val md = MessageDigest.getInstance("SHA-1")
        md.update(toBytes(namespace))
        md.update(nameBytes)
        val hash = md.digest()

        hash[6] = (hash[6].toInt() and 0x0f or 0x50).toByte()
        hash[8] = (hash[8].toInt() and 0x3f or 0x80).toByte()

        return toUuidString(hash)
    }

    private fun toBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        return ByteArray(16).apply {
            for (i in 0..7) this[7 - i] = (msb shr (i * 8)).toByte()
            for (i in 0..7) this[15 - i] = (lsb shr (i * 8)).toByte()
        }
    }

    private fun toUuidString(hash: ByteArray): String {
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = msb shl 8 or (hash[i].toLong() and 0xff)
        for (i in 8..15) lsb = lsb shl 8 or (hash[i].toLong() and 0xff)
        return UUID(msb, lsb).toString()
    }
}
