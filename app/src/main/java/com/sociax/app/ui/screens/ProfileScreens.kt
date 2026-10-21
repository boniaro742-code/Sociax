package com.sociax.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sociax.app.data.ApiClient
import com.sociax.app.data.ApiException
import com.sociax.app.data.Me
import com.sociax.app.data.loadJpeg
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.launch

/** Round profile picture: real photo if set, gradient placeholder otherwise. */
@Composable
fun Avatar(url: String?, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Teal, BlueMid))),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) AsyncImage(url, "Profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun EditProfileScreen(me: Me, api: ApiClient, onSaved: (Me) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf(me.displayName.orEmpty()) }
    var username by remember { mutableStateOf(me.username.orEmpty().removePrefix("@")) }
    var bio by remember { mutableStateOf(me.bio) }
    var isPrivate by remember { mutableStateOf(me.isPrivate) }
    var current by remember { mutableStateOf(me) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val errNameEmpty = tr("err_name_empty"); val msgNoServer = tr("no_server"); val errUsername = tr("username_invalid")
    val msgPhotoOk = tr("photo_updated"); val msgPhotoFail = tr("photo_failed")

    // Android photo picker: no storage permission needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; message = null
            try {
                val bytes = loadJpeg(context, uri)
                val sig = api.signUpload("avatar", bytes.size)
                api.uploadToCloudinary(sig, bytes)
                current = api.confirmAvatar(sig.publicId)
                onSaved(current)
                message = msgPhotoOk; isError = false
            } catch (e: ApiException) {
                message = e.message; isError = true
            } catch (e: Exception) {
                message = msgPhotoFail; isError = true
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text(tr("edit_profile"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.clickable(enabled = !busy) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Avatar(current.avatarUrl, 110)
            Box(
                Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape).background(Teal),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.CameraAlt, "Change photo", tint = Navy, modifier = Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        GlassField(name, { name = it.take(50) }, tr("display_name"))
        Spacer(Modifier.height(12.dp))
        GlassField(username, { v -> username = v.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' }.take(20) }, tr("edit_username_hint"))
        Text(tr("username_rule"), color = Color.White.copy(.5f), fontSize = 11.sp, modifier = Modifier.align(Alignment.Start).padding(start = 12.dp, top = 2.dp))
        Spacer(Modifier.height(12.dp))
        GlassField(bio, { bio = it.take(150) }, tr("bio_hint"))
        Text("${bio.length}/150", color = Color.White.copy(.5f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
        Spacer(Modifier.height(14.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("private_acc"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(tr("private_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(isPrivate, { isPrivate = it })
            }
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = if (isError) Color(0xFFFF8A8A) else Teal, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        if (busy) CircularProgressIndicator(color = Teal)
        else PillButton(tr("save"), {
            if (name.isBlank()) { message = errNameEmpty; isError = true; return@PillButton }
            if (username.length < 3) { message = errUsername; isError = true; return@PillButton }
            scope.launch {
                busy = true; message = null
                try {
                    val newName = if (username != current.username.orEmpty().removePrefix("@")) username else null
                    current = api.updateProfile(name.trim(), bio.trim(), isPrivate, newName)
                    onSaved(current); onBack()
                } catch (e: ApiException) {
                    message = e.message; isError = true
                } catch (e: Exception) {
                    message = msgNoServer; isError = true
                }
                busy = false
            }
        }, Modifier.fillMaxWidth())
    }
}
