package com.sociax.app.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sociax.app.data.ApiClient
import com.sociax.app.data.ApiException
import com.sociax.app.data.Post
import com.sociax.app.data.ShortVideo
import com.sociax.app.data.StoryGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Step 75 (plan v4.0.1 "Home video tap -> full-screen Short Video"): the Home feed lives HERE, outside the Home screen, so it survives while a
 * page (the full-screen video, its comments, a creator's profile) is open on top of it. Coming back shows the same rows at the same scroll
 * position: nothing is reloaded and the list is not reset to the top. Everything is wiped on log out ([reset]).
 */
class HomeFeedState {
    val posts = mutableStateListOf<Post>()
    val videos = mutableStateListOf<ShortVideo>()
    val order = mutableStateListOf<HomeRef>()          // the rows in the order they are shown (Step 74)
    val listState = LazyListState()                     // scroll position: kept while the Home screen is not on screen

    var tab by mutableStateOf("for_you")
    var loading by mutableStateOf(true)
    var endReached by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var reload by mutableIntStateOf(0)
    var storyGroups by mutableStateOf<List<StoryGroup>>(emptyList())
    var pageFailed by mutableStateOf(false)             // a failed "next page" must not retry in a tight loop; "Try again" restarts

    // paging (not shown on screen, so not observed)
    var cursor: String? = null                           // Step 40: ranked For You photos; null = start
    var rankedMode = false                               // false = the server answered newest-first: paging continues with "before"
    var videoCursor: String? = null
    var videoRanked = true
    var videoEnd = false                                 // no more videos for this feed (or they could not be loaded: photos go on)
    var photoEnd = false

    /** What the rows on screen were loaded for ("tab/refreshKey/reload"). Same key again = nothing to reload. */
    var signature: String? = null
    var loadedAt = 0L
    /** Set when a page that Home must come back to (the full-screen video) is opened: the next entry keeps the rows, however old. */
    var keepOnReturn = false

    private var viewerSeed: Set<Long> = emptySet()

    /** True when the rows already on screen are the right ones: the same load, and either a "come back" or younger than [MAX_AGE_MS]. */
    fun canKeep(key: String, now: Long = System.currentTimeMillis()): Boolean =
        signature == key && order.isNotEmpty() && (keepOnReturn || now - loadedAt < MAX_AGE_MS)

    fun reset() {
        posts.clear(); videos.clear(); order.clear()
        tab = "for_you"; loading = true; endReached = false; notice = null; reload = 0; storyGroups = emptyList(); pageFailed = false
        cursor = null; rankedMode = false; videoCursor = null; videoRanked = true; videoEnd = false; photoEnd = false
        signature = null; loadedAt = 0L; keepOnReturn = false; viewerSeed = emptySet()
    }

    suspend fun loadPage(api: ApiClient, reset: Boolean) {
        loading = true
        try {
            coroutineScope {
                if (reset) { cursor = null; rankedMode = tab == "for_you"; videoCursor = null; videoRanked = true; videoEnd = false; photoEnd = false }
                // On For You the next page of videos is asked for at the same time as the next page of photos. Following stays photos only:
                // the server has no "videos of people I follow" list (README Step 59), and a guess here would show a half-empty list.
                val videoJob = if (tab == "for_you" && !videoEnd) {
                    val vc = if (reset) null else videoCursor
                    val vr = videoRanked
                    val lastId = if (reset) null else videos.lastOrNull()?.id
                    async { loadHomeVideos(api, vc, vr, lastId) }
                } else null
                var page: List<Post> = emptyList()
                var last = photoEnd
                if (!photoEnd) {
                    if (rankedMode) {
                        val r = api.feedForYou(if (reset) null else cursor)
                        cursor = r.nextCursor
                        if (!r.ranked) rankedMode = false
                        page = r.items
                        last = if (r.ranked) r.nextCursor == null else r.items.size < 20
                    } else {
                        page = api.feed(tab, if (reset) null else posts.lastOrNull()?.id)
                        last = page.size < 20
                    }
                }
                val vPart = videoJob?.await()
                if (reset) { posts.clear(); videos.clear(); order.clear(); endReached = false }
                val freshPosts = page.filter { n -> posts.none { it.id == n.id } }
                posts.addAll(freshPosts)
                var freshVideos: List<ShortVideo> = emptyList()
                if (videoJob != null) {
                    if (vPart != null) {
                        videoCursor = vPart.cursor
                        if (!vPart.ranked) videoRanked = false
                        freshVideos = vPart.items.filter { n -> videos.none { it.id == n.id } }
                        videos.addAll(freshVideos)
                        if (vPart.last) videoEnd = true
                    } else {
                        videoEnd = true     // the videos could not be loaded: the photos are never held back; videos come back after a refresh
                    }
                }
                order.addAll(mixHomeRefs(freshPosts.map { it.id }, freshVideos.map { it.id }))
                if (last) photoEnd = true
                endReached = photoEnd && (tab != "for_you" || videoEnd)
                if (reset) { notice = null; loadedAt = System.currentTimeMillis() }
                pageFailed = false
            }
        } catch (e: CancellationException) {
            if (reset) signature = null        // a first page that never finished must be asked for again
            loading = false
            throw e
        } catch (e: ApiException) {
            notice = e.message; pageFailed = true
        } catch (e: Exception) {
            notice = "Cannot reach the Sociax server. Is the backend running?"; pageFailed = true
        }
        loading = false
    }

    /**
     * The state of the full-screen player that opens when a Home video is tapped. It is NOT a second feed: it starts from the videos Home
     * already shows (same server rules, same ranking), the tapped one is the page that opens, and paging goes on from where Home stopped.
     * Returns null when that video is no longer in the list.
     */
    fun makeViewer(startId: Long): ShortsState? {
        val i = videos.indexOfFirst { it.id == startId }
        if (i < 0) return null
        val s = ShortsState()
        s.items.addAll(videos)
        s.page = i
        s.loaded = true
        s.cursor = videoCursor
        s.ranked = videoRanked
        s.endReached = videoEnd
        viewerSeed = videos.map { it.id }.toSet()
        keepOnReturn = true
        return s
    }

    /**
     * Back from the full-screen player: what the person did there (like, save, follow, delete, "Not interested", "Hide creator") and
     * what turned out to be unavailable is applied to the Home rows, so Home and the player never disagree. No reload.
     */
    fun absorbViewer(v: ShortsState) {
        val now = v.items.associateBy { it.id }
        val gone = v.unavailable.toSet()
        for (i in videos.indices) { now[videos[i].id]?.let { videos[i] = it } }
        videos.removeAll { (it.id in viewerSeed && it.id !in now) || it.id in gone }
        if (v.hiddenCreators.isNotEmpty()) {
            videos.removeAll { it.username in v.hiddenCreators }
            posts.removeAll { it.username in v.hiddenCreators }
        }
        val follow = HashMap<String, String>()
        v.items.forEach { follow[it.username] = it.followStatus }
        for (i in videos.indices) follow[videos[i].username]?.let { st -> if (videos[i].followStatus != st) videos[i] = videos[i].copy(followStatus = st) }
        for (i in posts.indices) follow[posts[i].username]?.let { st -> if (posts[i].followStatus != st) posts[i] = posts[i].copy(followStatus = st) }
        viewerSeed = emptySet()
    }

    companion object {
        /** Switching to another tab and back within this time keeps the feed as it is; older = it is loaded again. */
        const val MAX_AGE_MS = 120_000L
    }
}
