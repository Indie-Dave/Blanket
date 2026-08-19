package com.dnslock.family

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional blocks for YouTube Shorts and Instagram Reels, both in the native
 * apps and when those URLs are opened in a browser.
 */
object ShortFormBlockManager {

    enum class Kind { YOUTUBE_SHORTS, INSTAGRAM_REELS }

    private const val PREFS_NAME = "short_form_block"
    private const val KEY_YOUTUBE_SHORTS = "block_youtube_shorts"
    private const val KEY_INSTAGRAM_REELS = "block_instagram_reels"

    val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.vanced.android.youtube",
        "app.revanced.android.youtube"
    )

    val INSTAGRAM_PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    private val youtubeShortsViewIdHints = listOf(
        "reel_player_page_container",
        "reel_player_underlay",
        "reel_recycler",
        "reel_watch_fragment",
        "reel_foreground_overlay",
        "shorts_container",
        "shorts_pivot_page"
    )

    private val instagramReelsPlayerViewIdHints = listOf(
        "clips_viewer_view_pager",
        "clips_video_container",
        "clips_viewer_media_layout"
    )

    fun isYoutubeShortsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_YOUTUBE_SHORTS, false)

    fun setYoutubeShortsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_YOUTUBE_SHORTS, blocked).apply()
    }

    fun isInstagramReelsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INSTAGRAM_REELS, false)

    fun setInstagramReelsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_INSTAGRAM_REELS, blocked).apply()
    }

    fun isAnyEnabled(context: Context): Boolean =
        isYoutubeShortsBlocked(context) || isInstagramReelsBlocked(context)

    fun isYoutubePackage(packageName: String): Boolean = packageName in YOUTUBE_PACKAGES

    fun isInstagramPackage(packageName: String): Boolean = packageName in INSTAGRAM_PACKAGES

    fun urlLooksLikeYoutubeShorts(value: String): Boolean {
        val lower = value.lowercase()
        return (lower.contains("youtube.com") || lower.contains("youtu.be")) &&
            lower.contains("/shorts")
    }

    fun urlLooksLikeInstagramReels(value: String): Boolean {
        val lower = value.lowercase()
        if (!lower.contains("instagram.com")) return false
        return lower.contains("/reels") || Regex("""/reel/""").containsMatchIn(lower)
    }

    fun detectInTree(
        root: AccessibilityNodeInfo?,
        packageName: String,
        extraTexts: Collection<String> = emptyList(),
        youtubeEnabled: Boolean,
        instagramEnabled: Boolean,
        inBrowser: Boolean
    ): Kind? {
        if (root == null) return null

        if (youtubeEnabled) {
            extraTexts.firstOrNull { urlLooksLikeYoutubeShorts(it) }?.let {
                return Kind.YOUTUBE_SHORTS
            }
        }
        if (instagramEnabled) {
            extraTexts.firstOrNull { urlLooksLikeInstagramReels(it) }?.let {
                return Kind.INSTAGRAM_REELS
            }
        }

        val scanYoutube = youtubeEnabled && (isYoutubePackage(packageName) || inBrowser)
        val scanInstagram = instagramEnabled && (isInstagramPackage(packageName) || inBrowser)

        return scanNode(
            node = root,
            youtubeEnabled = scanYoutube,
            instagramEnabled = scanInstagram,
            depth = 0
        )
    }

    private fun scanNode(
        node: AccessibilityNodeInfo?,
        youtubeEnabled: Boolean,
        instagramEnabled: Boolean,
        depth: Int
    ): Kind? {
        if (node == null || depth > 22) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val selected = node.isSelected

        if (youtubeEnabled) {
            if (urlLooksLikeYoutubeShorts(text) || urlLooksLikeYoutubeShorts(desc)) {
                return Kind.YOUTUBE_SHORTS
            }
            if (youtubeShortsViewIdHints.any { viewId.endsWith(it) || viewId.contains("/$it") }) {
                return Kind.YOUTUBE_SHORTS
            }
            if (selected && isShortsTabLabel(text, desc)) {
                return Kind.YOUTUBE_SHORTS
            }
        }

        if (instagramEnabled) {
            if (urlLooksLikeInstagramReels(text) || urlLooksLikeInstagramReels(desc)) {
                return Kind.INSTAGRAM_REELS
            }
            if (instagramReelsPlayerViewIdHints.any { viewId.endsWith(it) || viewId.contains("/$it") }) {
                return Kind.INSTAGRAM_REELS
            }
            if (selected && isReelsTabLabel(text, desc)) {
                return Kind.INSTAGRAM_REELS
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanNode(child, youtubeEnabled, instagramEnabled, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isShortsTabLabel(text: String, desc: String): Boolean {
        val labels = listOf(text, desc).map { it.lowercase() }
        return labels.any { it == "shorts" || it == "short" || it.contains("shorts tab") }
    }

    private fun isReelsTabLabel(text: String, desc: String): Boolean {
        val labels = listOf(text, desc).map { it.lowercase() }
        return labels.any {
            it == "reels" || it == "reel" || it == "clips" ||
                it.contains("reels tab") || it.contains("reel tab")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
