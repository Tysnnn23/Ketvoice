package com.ketvoice.app.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.Ket27AlertButtons
import com.ketvoice.app.ui.common.Ket27DialogCard
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/** The account's forms and alerts, drawn above the account sheet. */
@Composable
internal fun AccountOverlays() {
    AccountUi.form.value?.let { AccountFormDialog(it) }
    if (AccountUi.profileOpen.value) ProfileEditorDialog { AccountUi.profileOpen.value = false }
    if (AccountUi.confirmSignOut.value) SignOutAlert()
    if (AccountUi.confirmDelete.value) DeleteAlert()
}

/** Typed values of the form, kept while it is open. */
internal class AccountFormState {
    var name by mutableStateOf("")
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var showPassword by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Create an account or sign in: an e-mail and a password, nothing to confirm, no code to type. */
@Composable
internal fun AccountFormDialog(form: AccountUi.Form, state: AccountFormState = remember(form) { AccountFormState() }) {
    val signUp = form == AccountUi.Form.SignUp
    Ket27DialogCard({ if (!state.busy) AccountUi.form.value = null }, Modifier.testTag("account-form")) {
        FormHeader(signUp)
        if (signUp) KetTextField(state.name, { state.name = it.take(40); state.error = null }, "Nom affiché",
            Modifier.fillMaxWidth().testTag("account-name"), enabled = !state.busy)
        KetTextField(state.email, { state.email = it.take(254); state.error = null }, "E-mail",
            Modifier.fillMaxWidth().testTag("account-email"), enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(10.dp))
        PasswordField(state)
        state.error?.let { FormError(it) }
        FormButtons(signUp, state)
        if (!signUp) KetTextButton("Créer un compte", { state.error = null; AccountUi.form.value = AccountUi.Form.SignUp },
            Modifier.fillMaxWidth().padding(top = 4.dp), enabled = !state.busy)
    }
}

@Composable
private fun FormHeader(signUp: Boolean) {
    val theme = LocalTheme.current
    Text(if (signUp) "Créer votre compte Ketvoice" else "Se connecter à Ketvoice", color = theme.onBackground,
        style = Ket27.Type.headline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(if (signUp) "Retrouvez votre bibliothèque sur votre téléphone, votre tablette et votre Quest."
        else "Votre bibliothèque se synchronise dès la connexion.",
        color = theme.secondary, style = Ket27.Type.footnote, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 12.dp))
    if (signUp) Spacer(Modifier.height(0.dp))
}

@Composable
private fun PasswordField(state: AccountFormState) {
    KetTextField(state.password, { state.password = it.take(72); state.error = null }, "Mot de passe",
        Modifier.fillMaxWidth().testTag("account-password"), enabled = !state.busy,
        visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    KetTextButton(if (state.showPassword) "Masquer le mot de passe" else "Afficher le mot de passe",
        { state.showPassword = !state.showPassword }, Modifier.padding(top = 2.dp))
}

@Composable
private fun FormError(text: String) {
    Text(text, color = DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp).testTag("account-error"))
}

@Composable
private fun FormButtons(signUp: Boolean, state: AccountFormState) {
    val scope = rememberCoroutineScope()
    val ready = state.email.isNotBlank() && state.password.isNotEmpty() && (!signUp || state.name.isNotBlank())
    Ket27AlertButtons(confirmButton = {
        KetTextButton(if (state.busy) "Connexion…" else if (signUp) "S'inscrire" else "Se connecter", {
            state.busy = true; state.error = null
            scope.launch {
                runCatching {
                    if (signUp) AccountSession.signUp(state.name, state.email, state.password)
                    else AccountSession.signIn(state.email, state.password)
                }.onSuccess { AccountUi.form.value = null }
                    .onFailure { state.error = friendly(it) }
                state.busy = false
            }
        }, primary = true, enabled = ready && !state.busy)
    }, dismissButton = { KetTextButton("Annuler", { AccountUi.form.value = null }, enabled = !state.busy) },
        modifier = Modifier.padding(top = 12.dp))
}

/** The listener reads why it failed; a network failure says so plainly. */
internal fun friendly(error: Throwable): String = when (error) {
    is java.io.IOException -> "Pas de connexion internet."
    else -> error.message?.takeIf { it.isNotBlank() && it.length < 160 } ?: "Connexion impossible pour le moment."
}

@Composable
private fun SignOutAlert() {
    val scope = rememberCoroutineScope()
    AppAlertDialog(onDismissRequest = { AccountUi.confirmSignOut.value = false },
        title = { Text("Se déconnecter de Ketvoice ?") },
        text = { Text("Votre bibliothèque reste sur cet appareil. Elle ne se synchronise plus jusqu'à la prochaine connexion.") },
        confirmButton = { KetTextButton("Se déconnecter", {
            AccountUi.confirmSignOut.value = false
            scope.launch { runCatching { AccountSession.signOut() } }
        }, primary = true, destructive = true) },
        dismissButton = { KetTextButton("Annuler", { AccountUi.confirmSignOut.value = false }) })
}

@Composable
private fun DeleteAlert() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(onDismissRequest = { if (!busy) AccountUi.confirmDelete.value = false },
        title = { Text("Supprimer votre compte Ketvoice ?") },
        text = { Text("Votre profil, votre photo et la copie en ligne de votre bibliothèque sont supprimés définitivement. La musique de cet appareil est conservée.") },
        confirmButton = { KetTextButton(if (busy) "Suppression…" else "Supprimer", {
            busy = true
            scope.launch {
                runCatching { AccountSession.deleteAccount() }.onFailure { AccountUi.notice.value = friendly(it) }
                busy = false; AccountUi.confirmDelete.value = false
            }
        }, primary = true, destructive = true, enabled = !busy) },
        dismissButton = { KetTextButton("Annuler", { AccountUi.confirmDelete.value = false }, enabled = !busy) })
}
