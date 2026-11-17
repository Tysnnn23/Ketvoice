package com.ketvoice.app.ui.account

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountProfile
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AccountState
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.NowPlayingSync
import com.ketvoice.app.sync.RemoteSession
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** The Ketvoice account's screens drawn in software on the host (not a phone): sign-in, profile, « Reprendre ici ». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() {
        AccountSession.showForTest(AccountState())
        Profiles.showForTest(AccountProfile())
        NowPlayingSync.showForTest(null)
        AccountUi.form.value = null; AccountUi.profileOpen.value = false
        AppSettings.reduceAnimations.value = false
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun image(name: String) = compose.runOnIdle { save(compose.activity.window.decorView, name) }
    private fun dialogImage(name: String) = compose.runOnIdle {
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, name)
    }

    private fun page(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background)) { content() }
        } }
    }

    @Test fun signedOutTheSheetOffersToCreateAnAccountOrSignIn() {
        page(true) { AccountDialog() }
        compose.onNodeWithTag("account-sign-up").assertIsDisplayed()
        compose.onNodeWithTag("account-sign-in").assertIsDisplayed()
        compose.onNodeWithText("Continuer avec Discord").assertIsDisplayed()
        compose.onNodeWithText("Rien n'est envoyé tant que vous n'êtes pas connecté.").assertExists()
        image("account-signed-out")
    }

    @Test fun theSignUpFormAsksForANameAnEmailAndAPasswordOnly() {
        page(false) { AccountDialog() }
        compose.onNodeWithTag("account-sign-up").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-name").assertExists()
        compose.onNodeWithTag("account-email").performTextInput("lea@exemple.fr")
        compose.onNodeWithTag("account-password").performTextInput("motdepasse")
        // No code, no confirmation step: the form has no other field.
        compose.onAllNodesWithText("Code", substring = true).assertCountEquals(0)
        compose.onNodeWithText("S'inscrire").assertExists()
        dialogImage("account-sign-up")
    }

    @Test fun theSignInFormInTheDark() {
        page(true) { AccountDialog() }
        compose.onNodeWithTag("account-sign-in").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-email").performTextInput("lea@exemple.fr")
        compose.onNodeWithText("Se connecter à Ketvoice").assertExists()
        dialogImage("account-sign-in-dark")
    }

    @Test fun signedInTheSheetShowsTheProfileAndTheSync() {
        AccountSession.showForTest(AccountState("0b8f6d1e-7f3a-4c62-9a55-2f1c0e9d4b11", "lea@exemple.fr"))
        Profiles.showForTest(AccountProfile("Léa Martin", "lea.m"))
        page(true) { AccountDialog() }
        compose.onNodeWithText("Léa Martin").assertIsDisplayed()
        compose.onNodeWithText("@lea.m").assertIsDisplayed()
        compose.onNodeWithTag("account-edit-profile").assertIsDisplayed()
        compose.onNodeWithText("Supprimer le compte").assertExists()
        image("account-signed-in")
    }

    @Test fun theProfileEditorAndTheAvatarOnTheAccountCircle() {
        AccountSession.showForTest(AccountState("0b8f6d1e-7f3a-4c62-9a55-2f1c0e9d4b11", "lea@exemple.fr"))
        Profiles.showForTest(AccountProfile("Léa Martin", "lea.m"))
        page(false) {
            Column(Modifier.padding(24.dp)) { com.ketvoice.app.ui.common.AccountAvatar("K", 38.dp) }
            ProfileEditorDialog {}
        }
        compose.onNodeWithTag("profile-photo").assertExists()
        compose.onNodeWithText("Ajouter une photo").assertExists()
        dialogImage("account-profile-editor")
    }

    @Test fun thePhotoIsFramedSquareInACircle() {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this); c.drawColor(android.graphics.Color.rgb(40, 120, 200))
            c.drawCircle(150f, 100f, 70f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(250, 200, 60) })
        }
        page(true) { AvatarCropDialog(bitmap, {}, {}) }
        compose.onNodeWithTag("avatar-crop-frame").assertExists()
        dialogImage("account-avatar-crop")
    }

    @Test fun homeOffersToResumeWhatAnotherDevicePlays() {
        AccountSession.showForTest(AccountState("0b8f6d1e-7f3a-4c62-9a55-2f1c0e9d4b11", "lea@exemple.fr"))
        val track = Track("t:1", "Tout va bien", "Orelsan", "Civilisation", durationMs = 200_000)
        val session = RemoteSession("other-device-1", "Meta Quest 3", listOf(track), 0, 61_000, playing = true,
            at = com.ketvoice.app.account.ServerClock.now())
        page(true) {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                com.ketvoice.app.ui.common.PageTitle("Bonsoir", Modifier.padding(horizontal = 16.dp))
                com.ketvoice.app.ui.home.ResumeCard(session)
            }
        }
        compose.onNodeWithText("En cours sur Meta Quest 3").assertIsDisplayed()
        compose.onNodeWithTag("resume-here").assertIsDisplayed()
        image("account-resume-banner")
    }

    @Test fun theBannerStaysHiddenWhileSignedOut() {
        val track = Track("t:1", "Tout va bien", "Orelsan", "")
        NowPlayingSync.showForTest(RemoteSession("other", "Quest", listOf(track), 0, 0, true, 0))
        page(false) { com.ketvoice.app.ui.home.ResumeElsewhereBanner() }
        compose.onNodeWithTag("resume-elsewhere").assertDoesNotExist()
    }
}
