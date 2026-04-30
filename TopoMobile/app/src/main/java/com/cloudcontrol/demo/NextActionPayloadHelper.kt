package com.cloudcontrol.demo

internal object NextActionPayloadHelper {
    /** next_action 所需的 image_url：带 data:image/png;base64, 前缀；空截图用 1x1 占位图 */
    fun imageUrlFromRawBase64(rawBase64: String): String {
        return when {
            rawBase64.isBlank() -> "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
            rawBase64.startsWith("data:image/") || rawBase64.startsWith("http://") || rawBase64.startsWith("https://") -> rawBase64
            else -> "data:image/png;base64,$rawBase64"
        }
    }
}
