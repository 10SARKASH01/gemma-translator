package com.gemmatranslator.offline

data class SupportedLanguage(val code: String, val name: String) {
    val rtl: Boolean get() = code in setOf("ar", "fa", "ur")
}

object SupportedLanguages {
    val all = listOf(
        SupportedLanguage("ar", "Arabic"), SupportedLanguage("en", "English"),
        SupportedLanguage("es", "Spanish"), SupportedLanguage("ja", "Japanese"),
        SupportedLanguage("zh", "Chinese"), SupportedLanguage("ko", "Korean"),
        SupportedLanguage("fa", "Persian"), SupportedLanguage("ur", "Urdu"),
        SupportedLanguage("fr", "French"),
    )
    fun rotate(current: Int, other: Int, direction: Int): Int {
        require(direction == -1 || direction == 1)
        var next = (current + direction + all.size) % all.size
        if (next == other) next = (next + direction + all.size) % all.size
        return next
    }
}
