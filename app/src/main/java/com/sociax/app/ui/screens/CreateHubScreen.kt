package com.sociax.app.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sociax.app.ui.theme.*
import java.io.File

/**
 * Step 83 (plan v5.0.0 section 5): the ONE Create entry point. The "+" in the Home header (and the one on the Short Video page) opens this
 * Camera & Media Creator. It offers Take Photo, Record Video and Choose from Gallery in the same place; there are no separate
 * "photo upload" and "video upload" buttons.
 *
 * - Nothing is asked until a choice is made. Take Photo / Record Video use the phone's own camera app (it asks for its own camera and
 *   microphone permission); Choose from Gallery uses the system picker, which needs no storage permission.
 * - The kind of the result is detected here (the file's own type), not chosen by the person: a photo continues in the photo editor and post
 *   flow, a video in the video editor and Short Video flow. Both are the existing flows with their drafts, quota, retry and publish rules.
 * - Nothing is published here. Publishing always needs the explicit Publish button of the next screens.
 */
@Composable
fun CreateHubScreen(onBack: () -> Unit, onPhoto: (Uri) -> Unit, onVideo: (Uri) -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }       // the file the camera app is about to write
    val errCamera = tr("err_camera_open")
    val errType = tr("err_media_type")

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pending; pending = null
        if (ok && u != null) onPhoto(u)
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val u = pending; pending = null
        if (ok && u != null) onVideo(u)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) {
            // the item's own type decides: image/* -> photo path, video/* -> video path
            val mime = try { context.contentResolver.getType(picked) } catch (e: Exception) { null }
            when {
                mime != null && mime.startsWith("video/") -> { error = null; onVideo(picked) }
                mime != null && mime.startsWith("image/") -> { error = null; onPhoto(picked) }
                else -> error = errType
            }
        }
    }

    fun cameraFile(ext: String): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }       // covered by res/xml/file_paths.xml
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("create"), onBack)
        Text(tr("create_intro"), color = Ink.copy(.7f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 14.dp))
        ChoiceRow(Icons.Filled.PhotoCamera, tr("take_photo"), tr("take_photo_sub")) {
            error = null
            try { val u = cameraFile("jpg"); pending = u; takePhoto.launch(u) }
            catch (e: ActivityNotFoundException) { error = errCamera } catch (e: Exception) { error = errCamera }
        }
        Spacer(Modifier.height(12.dp))
        ChoiceRow(Icons.Filled.Videocam, tr("record_video"), tr("record_video_sub")) {
            error = null
            try { val u = cameraFile("mp4"); pending = u; recordVideo.launch(u) }
            catch (e: ActivityNotFoundException) { error = errCamera } catch (e: Exception) { error = errCamera }
        }
        Spacer(Modifier.height(12.dp))
        ChoiceRow(Icons.Filled.PhotoLibrary, tr("choose_gallery"), tr("gallery_sub")) {
            error = null
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        error?.let { Text(it, color = ErrorInk, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp)) }
    }
}

@Composable
private fun ChoiceRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), corner = 24, fill = LavenderFill) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Ink.copy(.65f), fontSize = 13.sp)
            }
        }
    }
}
