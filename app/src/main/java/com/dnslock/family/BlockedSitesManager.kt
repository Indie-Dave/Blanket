package com.dnslock.family

import android.content.Context

/**
 * Stores user-entered website domains to block and matches them against text
 * extracted from a browser's address bar (URL or page title).
 */
object BlockedSitesManager {

    private const val PREFS_NAME = "blocked_sites"
    private const val KEY_DOMAINS = "domains"

    fun getBlockedDomains(context: Context): List<String> =
        readDomains(context).sortedBy { it.lowercase() }

    fun addDomain(context: Context, input: String): Boolean {
        val normalized = normalizeInput(input) ?: return false

        val domains = readDomains(context)
        if (domains.any { it.equals(normalized, ignoreCase = true) }) return false

        domains.add(normalized)
        writeDomains(context, domains)
        return true
    }

    fun removeDomain(context: Context, domain: String) {
        val domains = readDomains(context)
        if (domains.removeAll { it.equals(domain, ignoreCase = true) }) {
            writeDomains(context, domains)
        }
    }

    /**
     * Returns the blocked domain that matches the given address-bar text, or null.
     * The text may be a full URL ("https://m.youtube.com/watch?v=..."), a bare
     * host, or a partial input the user is typing.
     */
    fun findMatchingDomain(context: Context, addressBarText: String?): String? {
        val text = addressBarText?.trim().orEmpty()
        if (text.isEmpty()) return null

        val blocked = readDomains(context)
        if (blocked.isEmpty()) return null

        val host = extractHost(text)
        val haystacks = buildList {
            add(text.lowercase())
            host?.let { add(it) }
        }

        return blocked.firstOrNull { domain ->
            haystacks.any { hostMatches(domain, it) }
        }
    }

    /**
     * Normalizes user input into a comparable domain: strips scheme, "www.",
     * path, query and port. Returns null if nothing usable remains.
     */
    fun normalizeInput(input: String): String? {
        val trimmed = input.trim().lowercase()
        if (trimmed.isEmpty()) return null

        val host = extractHost(trimmed) ?: return null
        val cleaned = host.removePrefix("www.").trim('.')
        if (cleaned.isEmpty() || cleaned.contains(' ')) return null
        return cleaned
    }

    /**
     * Public host extraction for callers that need the normalized host from
     * address-bar / URL-like text.
     */
    fun extractHostPublic(value: String): String? = extractHost(value)

    /** True when the host is the safe redirect target (google.com). */
    fun isSafeRedirectHost(hostOrUrl: String): Boolean {
        val host = extractHost(hostOrUrl) ?: hostOrUrl.lowercase().trim()
        val cleaned = host.removePrefix("www.").trim('.')
        return cleaned == "google.com" || cleaned.endsWith(".google.com")
    }

    private fun extractHost(value: String): String? {
        var v = value.trim().lowercase()
        if (v.isEmpty()) return null

        val schemeIndex = v.indexOf("://")
        if (schemeIndex >= 0) {
            v = v.substring(schemeIndex + 3)
        }

        v = v.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(' ')
            .substringBefore(':')
            .removePrefix("www.")
            .trim('.')

        return v.takeIf { it.isNotEmpty() }
    }

    /**
     * True when [candidateHost] equals the blocked domain or is a subdomain of it
     * (e.g. blocked "youtube.com" matches "m.youtube.com"). Falls back to a
     * substring check for partial/typed text without a clean host.
     */
    private fun hostMatches(blockedDomain: String, candidateHost: String): Boolean {
        val blocked = blockedDomain.removePrefix("www.").trim('.').lowercase()
        val candidate = candidateHost.removePrefix("www.").trim('.').lowercase()
        if (blocked.isEmpty() || candidate.isEmpty()) return false

        if (candidate == blocked) return true
        if (candidate.endsWith(".$blocked")) return true

        // Handle typed text / URLs where candidate still contains the domain.
        return candidate.contains(blocked)
    }

    private fun readDomains(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_DOMAINS, emptySet()).orEmpty())
    }

    private fun writeDomains(context: Context, domains: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DOMAINS, HashSet(domains))
            .apply()
    }
}
