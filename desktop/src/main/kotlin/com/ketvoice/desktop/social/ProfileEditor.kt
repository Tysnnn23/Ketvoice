package com.ketvoice.desktop.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.social.ProfileRules
import com.ketvoice.desktop.ui.CapsuleButton
import com.ketvoice.desktop.ui.Hairline
import com.ketvoice.desktop.ui.KText
import com.ketvoice.desktop.ui.SettingsCard
import com.ketvoice.desktop.ui.ThinSlider
import com.ketvoice.desktop.ui.ToggleRow
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

/** A few accents for the profile, and « none » (the app's own). */
internal val profileAccents = listOf(null, "#FA2D48", "#FF9500", "#FFCC00", "#34C759", "#00C7BE", "#0A84FF", "#5E5CE6", "#BF5AF2")

/** The edit mode of one's own profile: identity, accent, photo and banner, who sees it, « Partager mon écoute ». */
@Composable
internal fun ProfileEditor(me: MyProfile) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var name by remember(me) { mutableStateOf(me.displayName) }
    var handle by remember(me) { mutableStateOf(me.handle) }
    var bio by remember(me) { mutableStateOf(me.bio) }
    var accent by remember(me) { mutableStateOf(me.accent) }
    var public by remember(me) { mutableStateOf(me.isPublic) }
    var note by remember { mutableStateOf<String?>(null) }
    var framing by remember { mutableStateOf<Pair<ProfileImages.Kind, BufferedImage>?>(null) }
    Column(Modifier.widthIn(max = 640.dp).padding(bottom = 24.dp).testTag("profile-editor"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelCard {
            KText("Nom affiché", Ket27Desk.Type.footnote, color = ket.secondary)
            FieldLine(name, "Votre nom", "profile-name", 40) { name = it }
            Spacer(Modifier.height(8.dp))
            KText("Nom d'utilisateur", Ket27Desk.Type.footnote, color = ket.secondary)
            FieldLine(handle, "@nom", "profile-handle", 25) { handle = it.removePrefix("@").lowercase() }
            Spacer(Modifier.height(8.dp))
            KText("Bio", Ket27Desk.Type.footnote, color = ket.secondary)
            FieldLine(bio, "Quelques mots (160 caractères)", "profile-bio", ProfileRules.BIO_MAX) { bio = it }
            Spacer(Modifier.height(10.dp))
            KText("Couleur d'accent", Ket27Desk.Type.footnote, color = ket.secondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profileAccents.forEachIndexed { i, hex ->
                    val c = ProfileRules.accentArgb(hex)?.let { Color(it) } ?: ket.accent
                    Box(Modifier.size(26.dp).clip(CircleShape).background(c)
                        .border(if (accent == hex) 3.dp else 0.dp, ket.label, CircleShape).clickable { accent = hex }.testTag("profile-accent:$i"))
                }
            }
        }
        SettingsCard {
            ToggleRow("Profil public", public, "profile-public") { public = it }
            Hairline()
            ToggleRow("Partager mon écoute", me.shareListening, "profile-share") { on -> scope.launch { if (!Social.setShareListening(on)) note = "Choisissez d'abord un nom d'utilisateur dans votre profil" } }
        }
        NoticeLine("Un profil public se voit sur le site (ketvoice-web.vercel.app/u/…) avec les modules réglés sur Public. Privé, seuls vos amis le voient.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallPill("Changer la photo", "profile-photo") { pickImage()?.let { framing = ProfileImages.Kind.Avatar to it } ?: run { note = "Image illisible (JPEG, PNG, BMP ou GIF)" } }
            SmallPill("Changer la bannière", "profile-banner") { pickImage()?.let { framing = ProfileImages.Kind.Banner to it } ?: run { note = "Image illisible (JPEG, PNG, BMP ou GIF)" } }
            if (me.avatarPath != null) SmallPill("Retirer la photo", "profile-photo-remove") { scope.launch { Social.removePicture(ProfileImages.Kind.Avatar) } }
            if (me.bannerPath != null) SmallPill("Retirer la bannière", "profile-banner-remove") { scope.launch { Social.removePicture(ProfileImages.Kind.Banner) } }
        }
        CapsuleButton("Enregistrer", null, {
            scope.launch { note = Social.saveIdentity(name, handle, bio, accent, public) ?: "Profil enregistré" }
        }, filled = true, accent = ket.accent, modifier = Modifier.testTag("profile-save"))
        NoticeLine(note)
    }
    framing?.let { (kind, image) ->
        FrameDialog(kind, image, onDismiss = { framing = null }) { jpeg ->
            framing = null
            scope.launch { note = Social.setPicture(kind, jpeg) ?: "Image enregistrée" }
        }
    }
}

/** The picture chosen on the computer (JFileChooser, as the window's other pickers), read at a bounded size. */
private fun pickImage(): BufferedImage? {
    val chooser = javax.swing.JFileChooser().apply {
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "bmp", "gif")
    }
    if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.let(ProfileImages::read)
}

/** Framing a picture: zoom and move it in the square (photo) or the 3:1 frame (banner), then save. */
@Composable
private fun FrameDialog(kind: ProfileImages.Kind, image: BufferedImage, onDismiss: () -> Unit, onSave: (ByteArray) -> Unit) {
    val ket = LocalKet.current
    var zoom by remember { mutableFloatStateOf(0f) }
    var panX by remember { mutableFloatStateOf(.5f) }
    var panY by remember { mutableFloatStateOf(.5f) }
    var preview by remember { mutableStateOf<Pair<ByteArray, ImageBitmap?>?>(null) }
    LaunchedEffect(zoom, panX, panY) {
        preview = withContext(Dispatchers.Default) {
            val jpeg = ProfileImages.render(image, kind, 1f + zoom * 3f, panX * 2f - 1f, panY * 2f - 1f)
            jpeg to runCatching { com.ketvoice.desktop.ui.Covers.decode(jpeg, 800) }.getOrNull()
        }
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            Column(Modifier.width(420.dp).shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(ket.raised)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(20.dp).testTag("profile-frame"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KText(if (kind == ProfileImages.Kind.Avatar) "Cadrer la photo" else "Cadrer la bannière", Ket27Desk.Type.headline)
                Box(Modifier.fillMaxWidth().aspectRatio(kind.aspect).clip(if (kind == ProfileImages.Kind.Avatar) CircleShape else RoundedCornerShape(12.dp))
                    .background(ket.card)) {
                    preview?.second?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                KText("Zoom", Ket27Desk.Type.footnote, color = ket.secondary)
                ThinSlider(zoom, { zoom = it }, tag = "frame-zoom")
                KText("Horizontal", Ket27Desk.Type.footnote, color = ket.secondary)
                ThinSlider(panX, { panX = it }, tag = "frame-x")
                KText("Vertical", Ket27Desk.Type.footnote, color = ket.secondary)
                ThinSlider(panY, { panY = it }, tag = "frame-y")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallPill("Annuler", "frame-cancel", onClick = onDismiss)
                    SmallPill("Enregistrer", "frame-save", filled = true) { preview?.first?.let(onSave) }
                }
            }
        }
    }
}
