package dev.distillpod.android.voice

import android.app.Activity
import android.content.Context
import androidx.credentials.*
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.*
import com.google.firebase.auth.*
import dev.distillpod.android.R
import kotlinx.coroutines.tasks.await

/** Separate Firebase Auth instance: voice sign-in never binds or uploads the library. */
class VoiceIdentity(private val context: Context) {
    private val auth: FirebaseAuth get() {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "distillpod-voice" } ?: FirebaseApp.initializeApp(context,
            FirebaseOptions.Builder().setApplicationId(context.getString(R.string.sync_app_id))
                .setApiKey(context.getString(R.string.sync_api_key)).setProjectId(context.getString(R.string.sync_project_id)).build(), "distillpod-voice")
        return FirebaseAuth.getInstance(app)
    }
    suspend fun connect(activity: Activity) {
        check(context.getString(R.string.sync_web_client_id).isNotBlank()) { "Voice sign-in is not configured in this build" }
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.sync_web_client_id)).build()
        val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        require(result is CustomCredential && result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val credential = GoogleIdTokenCredential.createFrom(result.data)
        auth.signInWithCredential(GoogleAuthProvider.getCredential(credential.idToken, null)).await()
    }
    suspend fun token(): String = auth.currentUser?.getIdToken(false)?.await()?.token ?: error("Connect voice access on the phone first")
    fun disconnect() {
        FirebaseApp.getApps(context).firstOrNull { it.name == "distillpod-voice" }
            ?.let { FirebaseAuth.getInstance(it).signOut() }
    }
}
