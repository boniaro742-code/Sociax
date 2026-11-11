package com.sociax.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun errText(e: Exception) =
    if (e is ApiException) e.message ?: "Something went wrong" else "Cannot reach the Sociax server."

@Composable
internal fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onBack)
        Spacer(Modifier.width(12.dp))
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- Create post ----------------
@Composable
fun CreatePostScreen(api: ApiClient, onBack: () -> Unit, onPosted: () -> Unit, defaultFollowersOnly: Boolean = false, initialUri: Uri? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Step 83 (plan v5.0.0 section 5): a photo taken or chosen in the unified Create screen arrives as initialUri and opens the editor at once
    var uri by remember { mutableStateOf<Uri?>(initialUri) }
    // Step 76 (plan v3.10.4): choosing a photo opens the Photo Editor; the edits are only a list of choices (the file is never changed)
    var edits by remember { mutableStateOf(PhotoEdits()) }
    var editing by remember { mutableStateOf(initialUri != null) }
    var caption by remember { mutableStateOf("") }
    var followersOnly by remember { mutableStateOf(defaultFollowersOnly) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Step 35: a photo that is already on Cloudinary is remembered, so "try again" after a failed save sends only the post record
    // (no second upload, no orphan file). The server also treats a repeated request for the same upload as the same post.
    // Step 76: the remembered upload belongs to ONE photo AND ONE set of edits; a changed edit is uploaded again.
    var uploaded by remember { mutableStateOf<Triple<Uri, PhotoEdits, String>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) { uri = picked; edits = PhotoEdits(); editing = true }
    }
    val errPick = tr("err_choose_photo")
    val errRead = tr("err_edit_load")
    val errExport = tr("err_edit_export")

    val editUri = uri
    if (editing && editUri != null) {
        PhotoEditorScreen(editUri, edits, onCancel = { editing = false }, onDone = { edits = it; editing = false })
    } else {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("new_post"), onBack)
        GlassPanel(Modifier.fillMaxWidth().height(320.dp), corner = 28) {
            val shown = uri
            if (shown != null) EditedPhotoPreview(shown, edits, Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.AddPhotoAlternate, null, tint = Accent, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(tr("choose_photo"), color = Ink)
                Text(tr("videos_note"), color = Ink.copy(.6f), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (uri == null) tr("choose_gallery") else tr("change_photo"), {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, filled = false)
            if (uri != null) PillButton(tr("edit_photo"), { editing = true })
        }
        Spacer(Modifier.height(14.dp))
        GlassField(caption, { caption = it.take(2200) }, tr("write_caption"))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(tr("public"), { followersOnly = false }, filled = !followersOnly)
            PillButton(tr("followers_only"), { followersOnly = true }, filled = followersOnly)
        }
        error?.let { Text(it, color = ErrorInk, modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp) }
        Spacer(Modifier.height(20.dp))
        if (busy) CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
        else PillButton(tr("post_btn"), {
            val chosen = uri ?: run { error = errPick; return@PillButton }
            val chosenEdits = edits
            scope.launch {
                busy = true; error = null
                try {
                    val publicId = uploaded?.takeIf { it.first == chosen && it.second == chosenEdits }?.third ?: run {
                        // Reads the ORIGINAL again and applies turn / crop / filter / adjust once. A failure here keeps the draft and uploads nothing.
                        val bytes = try { loadEditedJpeg(context, chosen, chosenEdits, 1440) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = if (chosenEdits.isDefault) errRead else errExport; busy = false; return@launch }
                        catch (e: OutOfMemoryError) { error = errExport; busy = false; return@launch }
                        val sig = api.signUpload("post", bytes.size)
                        api.uploadToCloudinary(sig, bytes)
                        uploaded = Triple(chosen, chosenEdits, sig.publicId)
                        sig.publicId
                    }
                    // "Published" is shown only after the SERVER confirmed the post record (onPosted runs after createPost returns)
                    api.createPost(publicId, caption.trim(), if (followersOnly) "followers" else "public")
                    uploaded = null
                    onPosted()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // a permanent refusal (not a weak network, not a full daily allowance) means the file is gone: upload again next time
                    if (e is ApiException && e.code in 400..499 && e.reason != "media_full" && e.code != 429) uploaded = null
                    error = errText(e)
                }
                busy = false
            }
        }, Modifier.fillMaxWidth())
    }
    }
}

// ---------------- Comments ----------------
@Composable
fun CommentsScreen(api: ApiClient, post: Post, onBack: () -> Unit, onChanged: () -> Unit) {
    CommentsScaffold(
        load = { api.comments(post.id) to true },
        send = { api.addComment(post.id, it) },
        onBack = onBack, onChanged = onChanged, key = "p${post.id}",
        header = { PostCaptionHeader(post.username, post.caption, "pd${post.id}") }   // Step 84: the post's own caption on its detail page
    )
}

/** Shared comments page for posts and short videos. [load] returns (comments, commentsAllowed). */
@Composable
fun CommentsScaffold(
    load: suspend () -> Pair<List<Comment>, Boolean>,
    send: suspend (String) -> Unit,
    onBack: () -> Unit, onChanged: () -> Unit, key: Any,
    header: @Composable () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var allowed by remember { mutableStateOf(true) }
    var text by rememberSaveable(key) { mutableStateOf("") }          // Step 56: the unsent comment survives rotation
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        try { val (c, ok) = load(); comments = c; allowed = ok; error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }
    LaunchedEffect(key) { reload() }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            try { send(t); text = ""; reload(); onChanged() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(16.dp)) {
        TopBar(tr("comments"), onBack)
        header()
        Box(Modifier.weight(1f)) {
            if (loading) CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
            else if (!allowed) Text(tr("comments_off_video"), color = Ink.copy(.7f))
            else if (comments.isEmpty()) Text(tr("no_comments"), color = Ink.copy(.7f))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(comments, key = { it.id }) { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        Avatar(c.avatarUrl, 36)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row {
                                Text(c.username, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(timeAgo(c.createdAt), color = Ink.copy(.6f), fontSize = 12.sp)
                            }
                            Text(c.text, color = Ink.copy(.9f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = ErrorInk, fontSize = 13.sp) }
        if (allowed) InsetPill(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ComposerField(text, { text = it.take(500) }, tr("add_comment"), { submit() }, Modifier.weight(1f))
                GlassIconButton(Icons.Filled.Send, tr("a11y_send"), { submit() })
            }
        }
    }
}

// ---------------- Search ----------------
@Composable
fun SearchScreen(api: ApiClient, onBack: () -> Unit, onComments: (Post) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }                                 // Step 56: the typed query survives rotation
    var searchNow by remember { mutableIntStateOf(0) }                                    // Step 56: the keyboard's Search key: run now, do not wait 300 ms
    val searchHandled = remember { intArrayOf(0) }
    val hideKeyboard = rememberKeyboardHider()
    // Step 56: dragging the results closes the keyboard (the text stays), like every search page.
    val hideOnDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) hideKeyboard()
                return Offset.Zero
            }
        }
    }
    var users by remember { mutableStateOf<List<UserHit>>(emptyList()) }
    val posts = remember { mutableStateListOf<Post>() }
    var shortHits by remember { mutableStateOf<List<ShortVideo>>(emptyList()) }      // Step 26: public short videos
    var next by remember { mutableStateOf(SearchNext()) }                               // Step 45: where each list continues (null = no more)
    var shownQuery by remember { mutableStateOf("") }                                    // the text the CURRENT results are for
    var moreBusy by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf<ShortVideo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val ctl = remember { PostController(api, scope, posts) { notice = it } }
    val dataSaver = Prefs(LocalContext.current).dataSaver

    // Step 45: the next page of ONE list; what is already shown is never added twice (LazyColumn keys must be unique).
    fun loadMore(type: String, off: Int) {
        if (moreBusy || shownQuery.isEmpty()) return
        moreBusy = true
        scope.launch {
            try {
                val r = api.searchMore(shownQuery, type, off)
                when (type) {
                    "users" -> { users = (users + r.users).distinctBy { it.username }; next = next.copy(users = r.next.users) }
                    "posts" -> { val have = posts.map { it.id }.toSet(); posts.addAll(r.posts.filter { it.id !in have }); next = next.copy(posts = r.next.posts) }
                    else -> { shortHits = (shortHits + r.shorts).distinctBy { it.id }; next = next.copy(shorts = r.next.shorts) }
                }
                notice = null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = errText(e) }
            moreBusy = false
        }
    }

    LaunchedEffect(query, searchNow) {
        if (query.trim().length < 2) { users = emptyList(); posts.clear(); shortHits = emptyList(); next = SearchNext(); shownQuery = ""; notice = null; return@LaunchedEffect }
        if (searchNow != searchHandled[0]) searchHandled[0] = searchNow    // the Search key: no waiting
        else delay(300) // wait until the person stops typing
        loading = true
        try {
            val r = api.searchAll(query.trim())
            users = r.users; posts.clear(); posts.addAll(r.posts); shortHits = r.shorts; next = r.next; shownQuery = query.trim(); notice = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = errText(e) }
        loading = false
    }

    val clip = watching
    if (clip != null) {
        BackHandler { watching = null }
        SearchShortPlayer(clip, dataSaver) { watching = null }
        return
    }

    // Step 56: the title and the search box are OUTSIDE the scrolling list. Inside it, scrolling the results away disposed the box and
    // the keyboard closed in the middle of typing.
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        TopBar(tr("search"), onBack)
        Spacer(Modifier.height(12.dp))
        GlassField(                                                                      // Step 45: the server accepts 50 characters at most
            query, { query = it.take(50) }, tr("search_all_hint"),
            imeAction = ImeAction.Search, onAction = { searchNow++ }, clearable = true
        )
        Spacer(Modifier.height(12.dp))
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().nestedScroll(hideOnDrag), contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        notice?.let { item { Text(it, color = ErrorInk, fontSize = 13.sp) } }
        if (loading) item { CircularProgressIndicator(color = Accent) }
        if (!loading && query.trim().length >= 2 && users.isEmpty() && posts.isEmpty() && shortHits.isEmpty() && notice == null)
            item { Text(tr("no_results"), color = Ink.copy(.7f)) }
        if (users.isNotEmpty()) item { Text(tr("people"), color = Accent, fontWeight = FontWeight.Bold) }
        items(users, key = { it.username }) { u ->
            var status by remember(u.username) { mutableStateOf(u.followStatus) }
            val openProfile = LocalOpenProfile.current
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.avatarUrl, 44, Modifier.clickable { openProfile(u.username) })
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).clickable { openProfile(u.username) }) {
                        Text(u.username, color = Ink, fontWeight = FontWeight.Bold)
                        Text(u.displayName + if (u.isPrivate) " · " + tr("private_tag") else "", color = Ink.copy(.7f), fontSize = 12.sp)
                    }
                    val label = when (status) { "accepted" -> tr("following"); "pending" -> tr("requested"); else -> "+ " + tr("follow") }
                    PillButton(label, {
                        scope.launch {
                            try { status = api.setFollow(u.username, status == "none") }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { notice = errText(e) }
                        }
                    }, filled = status == "none")
                }
            }
        }
        next.users?.let { off -> item(key = "more_users") { LoadMoreButton(moreBusy) { loadMore("users", off) } } }
        if (posts.isNotEmpty()) item { Text(tr("posts"), color = Accent, fontWeight = FontWeight.Bold) }
        items(posts, key = { "p" + it.id }) { PostCard(it, ctl, onComments) }
        next.posts?.let { off -> item(key = "more_posts") { LoadMoreButton(moreBusy) { loadMore("posts", off) } } }
        if (shortHits.isNotEmpty()) item { Text(tr("short_videos"), color = Accent, fontWeight = FontWeight.Bold) }
        items(shortHits.chunked(3), key = { row -> "s" + row.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { sv ->
                    Box(
                        Modifier.weight(1f).aspectRatio(0.6f).clip(RoundedCornerShape(10.dp))
                            .background(FieldFill).clickable { watching = sv }
                    ) {
                        sv.posterUrl?.let { AsyncImage(it, sv.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        next.shorts?.let { off -> item(key = "more_shorts") { LoadMoreButton(moreBusy) { loadMore("shorts", off) } } }
    }
    }
}

@Composable
private fun LoadMoreButton(busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(color = Accent) else PillButton(tr("load_more"), onClick, filled = false)
    }
}

// ---------------- Follow requests (private accounts) ----------------
@Composable
fun RequestsScreen(api: ApiClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<FollowRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try { requests = api.followRequests() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        TopBar(tr("follow_requests"), onBack)
        error?.let { Text(it, color = ErrorInk) }
        if (loading) CircularProgressIndicator(color = Accent)
        else if (requests.isEmpty()) Text(tr("no_requests"), color = Ink.copy(.7f))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(requests, key = { it.username }) { r ->
                GlassPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(r.avatarUrl, 44)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.username, color = Ink, fontWeight = FontWeight.Bold)
                            Text(r.displayName, color = Ink.copy(.7f), fontSize = 12.sp)
                        }
                        PillButton(tr("accept"), { answer(scope, api, r, true, { requests = requests - r }) { error = it } })
                        Spacer(Modifier.width(6.dp))
                        PillButton(tr("decline"), { answer(scope, api, r, false, { requests = requests - r }) { error = it } }, filled = false)
                    }
                }
            }
        }
    }
}

private fun answer(scope: kotlinx.coroutines.CoroutineScope, api: ApiClient, r: FollowRequest, accept: Boolean,
                   done: () -> Unit, onError: (String) -> Unit) {
    scope.launch {
        try { api.answerRequest(r.username, accept); done() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { onError(errText(e)) }
    }
}
