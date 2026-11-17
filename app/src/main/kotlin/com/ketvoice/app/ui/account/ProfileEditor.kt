package com.ketvoice.app.ui.account

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AvatarImage
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.Ket27AlertButtons
import com.ketvoice.app.ui.common.Ket27DialogCard
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** The profile editor: the photo (picked, framed square, sent compressed), the name shown and an optional @handle. */
@Composable
internal fun ProfileEditorDialog(onDismiss: () -> Unit) {
    val profile by Profiles.profile.collectAsState()
    var name by remember { mutableStateOf(profile.displayName) }
    var handle by remember { mutableStateOf(profile.handle) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { AvatarImage.decode(context, uri) } }
                .onSuccess { picked = it }.onFailure { error = "Cette image ne peut pas être lue." }
        }
    }
    picked?.let { bitmap -> AvatarCropDialog(bitmap, onDone = { picked = null }, onError = { error = it }); return }
    Ket27DialogCard({ if (!busy) onDismiss() }, Modifier.testTag("profile-editor")) {
        Text("Profil", color = LocalTheme.current.onBackground, style = Ket27.Type.headline, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        ProfilePhoto(busy, onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemove = { scope.launch { runCatching { Profiles.removeAvatar() }.onFailure { error = friendly(it) } } })
        KetTextField(name, { name = it.take(40); error = null }, "Nom affiché", Modifier.fillMaxWidth().testTag("profile-name"), enabled = !busy)
        Spacer(Modifier.height(10.dp))
        KetTextField(handle, { handle = it.take(25); error = null }, "Nom d'utilisateur (facultatif)",
            Modifier.fillMaxWidth().testTag("profile-handle"), enabled = !busy)
        error?.let { Text(it, color = DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 6.dp)) }
        Ket27AlertButtons(confirmButton = {
            KetTextButton(if (busy) "Enregistrement…" else "Enregistrer", {
                busy = true
                scope.launch {
                    runCatching { Profiles.update(name, handle) }.onSuccess { onDismiss() }.onFailure { error = friendly(it) }
                    busy = false
                }
            }, primary = true, enabled = !busy && name.isNotBlank())
        }, dismissButton = { KetTextButton("Annuler", onDismiss, enabled = !busy) }, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun ProfilePhoto(busy: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val photo by Profiles.avatar.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onPick).testTag("profile-photo")) {
            KetvoiceAvatarContent(96.dp)
        }
        Row {
            KetTextButton(if (photo == null) "Ajouter une photo" else "Changer la photo", onPick, enabled = !busy)
            if (photo != null) KetTextButton("Retirer", onRemove, destructive = true, enabled = !busy)
        }
    }
}

/** Where the picture stands in the square frame: its zoom and where it is moved, each −1..1 from edge to edge. */
internal class CropState {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    /** A drag of [dx], [dy] frame pixels and a pinch of [factor] on a [width]×[height] picture shown in a [frame]-pixel square. */
    fun move(width: Int, height: Int, frame: Float, dx: Float, dy: Float, factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, 5f)
        val scale = frame / min(width, height) * zoom
        val side = min(width, height) / zoom
        if (width - side > 0.5f) panX = (panX - 2f * dx / scale / (width - side)).coerceIn(-1f, 1f)
        if (height - side > 0.5f) panY = (panY - 2f * dy / scale / (height - side)).coerceIn(-1f, 1f)
    }
}

/** Frames the picked picture in a circle-in-square: pinch to zoom, drag to place; « Utiliser » sends it. */
@Composable
internal fun AvatarCropDialog(bitmap: Bitmap, onDone: () -> Unit, onError: (String) -> Unit, crop: CropState = remember(bitmap) { CropState() }) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Ket27DialogCard({ if (!busy) onDone() }, Modifier.testTag("avatar-crop")) {
        Text("Cadrer la photo", color = LocalTheme.current.onBackground, style = Ket27.Type.headline, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        CropFrame(bitmap, crop, busy)
        Ket27AlertButtons(confirmButton = {
            KetTextButton(if (busy) "Envoi…" else "Utiliser", {
                busy = true
                scope.launch {
                    runCatching {
                        val jpeg = withContext(Dispatchers.Default) {
                            val square = AvatarImage.square(bitmap.width, bitmap.height, crop.zoom, crop.panX, crop.panY)
                            AvatarImage.compress(AvatarImage.render(bitmap, square))
                        }
                        Profiles.uploadAvatar(jpeg)
                    }.onFailure { onError(friendly(it)) }
                    busy = false; onDone()
                }
            }, primary = true, enabled = !busy)
        }, dismissButton = { KetTextButton("Annuler", onDone, enabled = !busy) }, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun CropFrame(bitmap: Bitmap, crop: CropState, busy: Boolean) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val accent = LocalTheme.current.accent
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .background(Color.Black).testTag("avatar-crop-frame"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(bitmap) {
            detectTransformGestures { _, pan, zoom, _ -> crop.move(bitmap.width, bitmap.height, size.width.toFloat(), pan.x, pan.y, zoom) }
        }) {
            val frame = size.width
            val square = com.ketvoice.app.account.AvatarImage.square(bitmap.width, bitmap.height, crop.zoom, crop.panX, crop.panY)
            val scale = frame / square.side
            drawImage(image, dstOffset = IntOffset((-square.left * scale).roundToInt(), (-square.top * scale).roundToInt()),
                dstSize = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt()))
            // What stays outside the circle the photo is shown in is dimmed.
            val radius = frame / 2f
            val outside = androidx.compose.ui.graphics.Path().apply {
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addOval(androidx.compose.ui.geometry.Rect(center, radius))
            }
            drawPath(outside, Color.Black.copy(alpha = .5f))
            drawCircle(accent.copy(alpha = .9f), radius = radius - 1.5f, style = Stroke(width = 3f))
        }
        if (busy) ActivitySpinner(size = 28.dp)
    }
}
