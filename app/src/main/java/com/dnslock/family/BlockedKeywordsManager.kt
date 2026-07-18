package com.dnslock.family

import android.content.Context

/**
 * Stores user-entered keywords and matches them as case-insensitive substrings
 * against browser address-bar text (URL, path, query, or title).
 */
object BlockedKeywordsManager {

    private const val PREFS_NAME = "blocked_keywords"
    private const val KEY_KEYWORDS = "keywords"
    private const val MIN_KEYWORD_LENGTH = 3

    fun getBlockedKeywords(context: Context): List<String> =
        readKeywords(context).sortedBy { it.lowercase() }

    fun addKeyword(context: Context, input: String): Boolean {
        val normalized = normalizeInput(input) ?: return false

        val keywords = readKeywords(context)
        if (keywords.any { it.equals(normalized, ignoreCase = true) }) return false

        keywords.add(normalized)
        writeKeywords(context, keywords)
        return true
    }

    fun removeKeyword(context: Context, keyword: String) {
        val keywords = readKeywords(context)
        if (keywords.removeAll { it.equals(keyword, ignoreCase = true) }) {
            writeKeywords(context, keywords)
        }
    }

    fun normalizeInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length < MIN_KEYWORD_LENGTH) return null
        return trimmed
    }

    /**
     * Returns the first blocked keyword found in [text], or null.
     * Uses case-insensitive matching with non-letter/digit boundaries so
     * short keywords do not match inside longer words (e.g. "go" vs "google").
     */
    fun findMatchingKeyword(context: Context, text: String?): String? {
        val haystack = text?.trim().orEmpty()
        if (haystack.isEmpty()) return null

        val keywords = readKeywords(context)
        if (keywords.isEmpty()) return null

        return keywords.firstOrNull { keyword ->
            containsKeyword(haystack, keyword)
        }
    }

    fun findMatchingKeyword(context: Context, texts: Collection<String>): String? {
        for (text in texts) {
            findMatchingKeyword(context, text)?.let { return it }
        }
        return null
    }

    private fun containsKeyword(haystack: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        val pattern = Regex(
            "(?<![A-Za-z0-9])${Regex.escape(keyword)}(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(haystack)
    }

    private fun readKeywords(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_KEYWORDS, emptySet()).orEmpty())
    }

    private fun writeKeywords(context: Context, keywords: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_KEYWORDS, HashSet(keywords))
            .apply()
    }
}
