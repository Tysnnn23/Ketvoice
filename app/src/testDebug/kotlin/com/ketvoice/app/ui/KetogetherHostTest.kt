package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.together.*
import com.ketvoice.app.ui.common.LocalSheetMaxHeight
import com.ketvoice.app.ui.together.KetogetherActions
import com.ketvoice.app.ui.together.KetogetherPill
import com.ketvoice.app.ui.together.KetogetherSheetContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Ketogether's sheet and live pill (6.1), rendered on the host, not a phone. Glass draws flat here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetogetherHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var language = ""
    private var chosen = false

    @org.junit.Before fun french() {
        language = com.ketvoice.app.data.AppSettings.language.value; chosen = com.ketvoice.app.data.AppSettings.languageChosen.value
        com.ketvoice.app.data.AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        com.ketvoice.app.data.AppSettings.languageChosen.value = true
    }

    @org.junit.After fun restore() {
        com.ketvoice.app.data.AppSettings.language.value = language; com.ketvoice.app.data.AppSettings.languageChosen.value = chosen
    }

    private val song = TrackRef("yt:abcdefghijk", "Owned QA tone", "QA Ensemble", "Tests", 240_000)
    private val next = TrackRef("yt:bcdefghijkl", "Second tone", "QA Ensemble", "Tests", 200_000)
    private val members = listOf(Member("host000001", "Léa Martin", host = true, me = true),
        Member("guest00001", "Tom", host = false), Member("guest00002", "Sam K", host = false), Member("guest00003", "Inès", host = false))
    private val hostUi = TogetherUi(TogetherPhase.Live, TogetherRole.Host, "ABC234", "QRSTUV23", members, "Léa Martin", song,
        playing = true, upcoming = listOf(QueueItem("e1", next, "Tom"), QueueItem("e2", null), QueueItem("e3", next)),
        pending = listOf(Proposal("p1", "guest00002", "Sam K", TrackRef("yt:cdefghijklm", "Proposed tone", "QA", ""))),
        policy = Policy(everyoneControls = false, everyoneAdds = false),
        reaction = Reaction("🔥", "Tom", 0, 1))

    private fun save(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The sheet as the glass panel holds it: a rounded card over a dimmed cover-coloured page. */
    @Composable
    private fun Sheet(dark: Boolean, content: @Composable () -> Unit) = KetvoiceTheme(useDark = dark) {
        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF2A1F35) else Color(0xFFD9D2C8)), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(34.dp))
                .background(LocalTheme.current.card.copy(alpha = .94f))) {
                CompositionLocalProvider(LocalSheetMaxHeight provides 1_400.dp) { content() }
            }
        }
    }

    @Test fun theStartOfTheSheetCreatesOrJoins() {
        var created = false
        var joined = false
        val code = mutableStateOf("")
        compose.setContent { Sheet(dark = true) {
            KetogetherSheetContent(TogetherUi(), null, "Léa", {}, null, code.value, { code.value = it }, invited = false,
                actions = KetogetherActions(create = { created = true }, join = { joined = true }))
        } }
        compose.onNodeWithTag("ketogether-name").assertIsDisplayed()
        compose.onNodeWithText("Créer une session").performClick()
        assertTrue(created)
        code.value = "ABC 234"
        compose.onNodeWithText("Rejoindre").performClick()
        assertTrue(joined)
        save("ketogether-start")
    }

    @Test fun anInvitationAsksBeforeJoining() {
        compose.setContent { Sheet(dark = false) {
            KetogetherSheetContent(TogetherUi(), "lea_discord", "", {}, null, "ABC 234", {}, invited = true, actions = KetogetherActions())
        } }
        compose.onNodeWithText("Invitation à une session").assertIsDisplayed()
        compose.onNodeWithText("ABC 234").assertIsDisplayed()
        compose.onNodeWithTag("ketogether-create").assertDoesNotExist()
        save("ketogether-invitation")
    }

    @Test fun theHostSeesCodeQrPeopleQueueAndRules() {
        val accepted = ArrayList<String>()
        val reactions = ArrayList<String>()
        compose.setContent { Sheet(dark = true) {
            KetogetherSheetContent(hostUi, null, "", {}, null, "", {}, false,
                KetogetherActions(accept = { accepted += it }, react = { reactions += it }), showQr = true)
        } }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("ketogether-code").assertTextEquals("ABC 234")
        compose.onNodeWithTag("ketogether-qr").assertExists()
        compose.onNodeWithText("Tout le monde contrôle").assertExists()
        compose.onNodeWithText("Titre réservé à l'hôte").assertExists()
        compose.onNodeWithText("Proposé par Tom").assertExists()
        save("ketogether-host")
        compose.onNodeWithText("Ajouter").performClick()
        assertEquals(listOf("p1"), accepted)
        compose.onAllNodesWithTag("ketogether-react")[1].performClick()
        assertEquals(listOf("🔥"), reactions)
    }

    @Test fun aGuestSeesAnUnavailableTitleAndTheHostsRules() {
        val guestUi = hostUi.copy(role = TogetherRole.Guest, nowPlaying = null, available = false, hostName = "Léa Martin",
            members = listOf(members[0].copy(me = false), members[1].copy(me = true)), reaction = null,
            policy = Policy(everyoneControls = false, everyoneAdds = true))
        compose.setContent { Sheet(dark = false) {
            KetogetherSheetContent(guestUi, null, "", {}, null, "", {}, false, KetogetherActions())
        } }
        compose.onNodeWithText("Titre indisponible pour les invités").assertExists()
        compose.onNodeWithText("Passer").assertDoesNotExist()
        compose.onNodeWithText("Ajouter").assertDoesNotExist()
        compose.onNodeWithText("Quitter la session").assertExists()
        compose.onNodeWithText("Tout le monde contrôle").assertDoesNotExist()
        save("ketogether-guest")
    }

    @Test fun theLivePillInThePlayerAndTheMiniPlayer() {
        val ui = mutableStateOf(hostUi.copy(reaction = null))
        compose.setContent { KetvoiceTheme(useDark = false) {
            Column(Modifier.fillMaxSize().background(Color(0xFFEFEFF4))) {
                // The phone player's foot: the pill where « Ce téléphone » was.
                Box(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF3B2A20)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        com.ketvoice.app.ui.common.Text("Ce téléphone", color = Color(0xFF777984), fontSize = 10.sp)
                        Spacer(Modifier.height(16.dp))
                        KetogetherPill(ui.value, onClick = null)
                    }
                }
                // The mini-player's second line, light glass.
                Row(Modifier.padding(16.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(Color.White)
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        com.ketvoice.app.ui.common.Text("Owned QA tone", localize = false, fontSize = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KetogetherPill(ui.value, Modifier.padding(end = 5.dp), compact = true, onDark = false, onClick = null)
                            com.ketvoice.app.ui.common.Text("QA Ensemble", localize = false, fontSize = 12.5.sp, color = Color.Gray)
                        }
                    }
                }
                // Reconnecting: the dot turns amber.
                Box(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
                    KetogetherPill(hostUi.copy(phase = TogetherPhase.Reconnecting, reaction = null), onClick = null)
                }
            }
        } }
        compose.onAllNodesWithTag("ketogether-pill").assertCountEquals(3)
        compose.onAllNodesWithText("4")[0].assertExists()
        ui.value = hostUi.copy(reaction = Reaction("🎶", "Tom", 1, 7))
        compose.mainClock.advanceTimeBy(400)
        compose.onAllNodesWithText("🎶")[0].assertExists()
        save("ketogether-pills")
        // Outside a session nothing is drawn.
        ui.value = TogetherUi()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onAllNodesWithTag("ketogether-pill").assertCountEquals(1)
    }
}
