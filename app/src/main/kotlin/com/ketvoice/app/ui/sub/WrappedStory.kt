package com.ketvoice.app.ui.sub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.ArtworkFlowBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The pages of the story, in order. */
internal object WrappedPages {
    const val COUNT = 5
    const val PAGE_MS = 6_500L
    /** A tap on the left third goes back, anywhere else forward. */
    fun next(page: Int, x: Float, width: Float): Int =
        if (width > 0f && x < width / 3f) (page - 1).coerceAtLeast(0) else (page + 1).coerceAtMost(COUNT)
}

@Composable
internal fun rememberWrappedRecap(): Wrapped.Recap {
    val years by ListeningHistory.years.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val listened by ListeningHistory.listenedMs.collectAsState()
    return remember(years, counts, known, listened) {
        Wrapped.recap(java.time.LocalDate.now().year.toString(), years, counts, listened, known,
            coverOf = { ArtworkRepo.coverForTrack(it) ?: it.coverUrl })
    }
}

/** Ketvoice Wrapped, as stories: the year in five pages over the flowing colours of the most played cover. */
@Composable
internal fun WrappedStory(recap: Wrapped.Recap, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var page by remember { mutableIntStateOf(0) }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(page) {
            if (page >= WrappedPages.COUNT) { onClose(); return@LaunchedEffect }
            progress.snapTo(0f)
            // The last page waits for the listener: it holds the share button.
            if (page == WrappedPages.COUNT - 1) { progress.snapTo(1f); return@LaunchedEffect }
            progress.animateTo(1f, tween(WrappedPages.PAGE_MS.toInt(), easing = LinearEasing))
            page++
        }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("wrapped-story")) {
            ArtworkFlowBackground(recap.topTrack?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }, playing = true)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { offset -> page = WrappedPages.next(page, offset.x, size.width.toFloat()) }
            }) {
                AnimatedContent(page.coerceAtMost(WrappedPages.COUNT - 1), transitionSpec = {
                    (fadeIn(tween(Motion.duration(420))) + scaleIn(tween(Motion.duration(420)), initialScale = .94f))
                        .togetherWith(fadeOut(tween(Motion.duration(220))))
                }, label = "wrapped-page") { shown ->
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 72.dp),
                        verticalArrangement = Arrangement.Center) {
                        when (shown) {
                            0 -> WrappedIntro(recap)
                            1 -> WrappedMinutes(recap)
                            2 -> WrappedArtist(recap)
                            3 -> WrappedTracks(recap)
                            else -> WrappedSummary(recap)
                        }
                    }
                }
            }
            // Story bars and the close button above everything.
            Column(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(WrappedPages.COUNT) { index ->
                        val fill = when { index < page -> 1f; index == page -> progress.value; else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .30f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("KETVOICE WRAPPED", localize = false, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    // Ket 27: the round 44 dp button of an iOS story.
                    val side = if (com.ketvoice.app.theme.Ket27.on) 44.dp else 40.dp
                    Box(Modifier.size(side).clip(CircleShape).background(Color.White.copy(alpha = if (com.ketvoice.app.theme.Ket27.on) .22f else .16f))
                        .clickable(role = Role.Button, onClick = onClose).testTag("wrapped-close"), contentAlignment = Alignment.Center) {
                        Icon(KetIcons.Close, "Fermer", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Headline(text: String, size: Int = 40) {
    // Ket 27: a display title as iOS sets one — bold rather than black, with the tracking that suits Inter.
    val ket = com.ketvoice.app.theme.Ket27.on
    Text(text, localize = false, color = Color.White, fontSize = size.sp, fontWeight = if (ket) FontWeight.Bold else FontWeight.Black,
        letterSpacing = if (ket) (-size * .018f).sp else (-1.2).sp, lineHeight = (size + 4).sp)
}

@Composable
private fun Caption(text: String) {
    if (com.ketvoice.app.theme.Ket27.on) Text(text, localize = false, color = Color.White.copy(alpha = .82f),
        style = com.ketvoice.app.theme.Ket27.Type.body, modifier = Modifier.padding(top = 10.dp))
    else Text(text, localize = false, color = Color.White.copy(alpha = .82f), fontSize = 17.sp, lineHeight = 23.sp,
        modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun WrappedIntro(recap: Wrapped.Recap) {
    Headline(if (recap.year != null) AppText.t("Votre année {0}").replace("{0}", recap.year) else AppText.t("Votre Ketvoice"), 46)
    Caption(if (recap.year != null) AppText.t("Ce que vous avez écouté cette année, en cinq écrans.")
        else AppText.t("Tout ce que Ketvoice a compté depuis vos débuts, en cinq écrans."))
}

@Composable
private fun WrappedMinutes(recap: Wrapped.Recap) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(recap.minutes) {
        if (Motion.reduced) shown.snapTo(recap.minutes.toFloat())
        else shown.animateTo(recap.minutes.toFloat(), tween(1_800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    Caption(AppText.t("Vous avez écouté"))
    Text("%,d".format(shown.value.toLong()).replace(',', ' '), localize = false, color = Color.White, fontSize = 72.sp,
        fontWeight = if (com.ketvoice.app.theme.Ket27.on) FontWeight.Bold else FontWeight.Black,
        letterSpacing = (-2.5).sp, modifier = Modifier.testTag("wrapped-minutes"))
    Headline(AppText.t("minutes de musique"), 30)
    Caption(AppText.t("{0} écoutes de {1} titres.").replace("{0}", recap.plays.toString()).replace("{1}", recap.titles.toString()))
}

@Composable
private fun WrappedArtist(recap: Wrapped.Recap) {
    val artist = recap.topArtist
    if (artist == null) { Headline(AppText.t("Votre artiste vous attend encore")); return }
    Caption(AppText.t("Votre artiste numéro 1"))
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Cover(artist.name, artist.cover, 240.dp, 120.dp)
    }
    Spacer(Modifier.height(22.dp))
    Text(artist.name, localize = false, color = Color.White, fontSize = 40.sp,
        fontWeight = if (com.ketvoice.app.theme.Ket27.on) FontWeight.Bold else FontWeight.Black,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    Text(AppText.t("{0} écoutes").replace("{0}", artist.plays.toString()), localize = false, color = Color.White.copy(alpha = .82f),
        fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
private fun WrappedTracks(recap: Wrapped.Recap) {
    Headline(AppText.t("Vos titres en boucle"), 34)
    Spacer(Modifier.height(18.dp))
    recap.tracks.forEachIndexed { index, (track, plays) ->
        val appear = remember { Animatable(if (Motion.reduced) 1f else 0f) }
        LaunchedEffect(Unit) { delay(index * 140L); appear.animateTo(1f, tween(Motion.duration(420))) }
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 24f }, verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", localize = false, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.width(34.dp))
            Cover(track.title, ArtworkRepo.coverForTrack(track) ?: track.coverUrl, 54.dp, 10.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(track.title, localize = false, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} · ${AppText.t("{0} écoutes").replace("{0}", plays.toString())}", localize = false,
                    color = Color.White.copy(alpha = .72f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WrappedSummary(recap: Wrapped.Recap) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Headline(AppText.t("Votre récap"), 40)
    Spacer(Modifier.height(16.dp))
    Text("${recap.minutes} min · ${recap.plays} ${AppText.t("écoutes")}", localize = false, color = Color.White, fontSize = 20.sp,
        fontWeight = FontWeight.Bold)
    recap.topArtist?.let { Caption("${AppText.t("Artiste")} : ${it.name}") }
    recap.topTrack?.let { Caption("${AppText.t("Titre")} : ${it.title}") }
    Spacer(Modifier.height(28.dp))
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White)
        .clickable(role = Role.Button, enabled = !sharing) {
            sharing = true
            scope.launch {
                runCatching { WrappedCard.share(context, WrappedCard.render(context, recap)) }
                sharing = false
            }
        }.padding(horizontal = 26.dp, vertical = 14.dp).testTag("wrapped-share")) {
        Text(if (sharing) "…" else AppText.t("Partager mon récap"), localize = false, color = Color.Black, fontSize = 16.sp,
            fontWeight = FontWeight.Bold)
    }
}

/** The shareable picture of the recap, story-sized like the lyrics Moment. */
internal object WrappedCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    suspend fun render(context: Context, recap: Wrapped.Recap): File = withContext(Dispatchers.Default) {
        val bitmap = androidx.core.graphics.createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        val cover = recap.topTrack?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }?.let { url ->
            runCatching {
                val request = coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).size(900).build()
                (coil.Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        }
        // The most played cover, blurred to colour fields, as in the story.
        if (cover != null) {
            val tiny = cover.scale(10, 18)
            canvas.drawBitmap(tiny.scale(WIDTH, HEIGHT), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        } else canvas.drawColor(0xFF2C2C34.toInt())
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0x66000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        })
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fun paint(size: Float, alpha: Int = 255, typeface: Typeface = bold) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); this.alpha = alpha; textSize = size; this.typeface = typeface
        }
        canvas.drawText("KETVOICE WRAPPED", 90f, 190f, paint(40f, 220).apply { letterSpacing = .25f })
        canvas.drawText(if (recap.year != null) AppText.t("Votre année {0}").replace("{0}", recap.year) else AppText.t("Votre Ketvoice"),
            90f, 300f, paint(76f))
        if (cover != null) {
            val side = 560f; val left = 90f; val top = 380f
            val clip = Path().apply { addRoundRect(RectF(left, top, left + side, top + side), 40f, 40f, Path.Direction.CW) }
            canvas.withClip(clip) { drawBitmap(cover, null, RectF(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
        var y = 1080f
        canvas.drawText("${recap.minutes}", 90f, y, paint(150f)); y += 70f
        canvas.drawText(AppText.t("minutes de musique"), 90f, y, paint(46f, 220, Typeface.DEFAULT)); y += 120f
        recap.topArtist?.let {
            canvas.drawText(AppText.t("Artiste numéro 1"), 90f, y, paint(36f, 190, Typeface.DEFAULT)); y += 66f
            canvas.drawText(ellipsize(it.name, paint(60f)), 90f, y, paint(60f)); y += 110f
        }
        canvas.drawText(AppText.t("Titres en boucle"), 90f, y, paint(36f, 190, Typeface.DEFAULT)); y += 66f
        recap.tracks.take(5).forEachIndexed { index, (track, _) ->
            canvas.drawText(ellipsize("${index + 1}  ${track.title}", paint(46f)), 90f, y, paint(46f)); y += 64f
        }
        canvas.drawText("ketvoice.vercel.app", 90f, HEIGHT - 110f, paint(36f, 200, Typeface.DEFAULT))
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles { file -> file.name.startsWith("wrapped-") }?.forEach { it.delete() }
        val file = File(folder, "wrapped-${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
        file
    }

    private fun ellipsize(text: String, paint: TextPaint): String =
        android.text.TextUtils.ellipsize(text, paint, WIDTH - 180f, android.text.TextUtils.TruncateAt.END).toString()

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, AppText.t("Mon Ketvoice Wrapped") + " · https://ketvoice.vercel.app")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager mon récap")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
