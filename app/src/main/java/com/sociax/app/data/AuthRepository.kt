package com.sociax.app.data

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/** Google sign-in through Firebase Auth. Needs app/google-services.json. */
class AuthRepository(private val context: Context) {

    private val auth: FirebaseAuth? by lazy {
        if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance()
    }

    val isConfigured: Boolean get() = auth != null
    val isSignedIn: Boolean get() = auth?.currentUser != null

    suspend fun idToken(): String? = auth?.currentUser?.getIdToken(false)?.await()?.token

    /** Shows the Google account chooser and returns the Google ID token. */
    private suspend fun googleIdToken(activity: Activity): String {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        require(resId != 0) { "default_web_client_id missing. Enable Google sign-in in Firebase and re-download google-services.json." }

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(context.getString(resId))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val cred = result.credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type"
        }
        return GoogleIdTokenCredential.createFrom(cred.data).idToken
    }

    suspend fun signInWithGoogle(activity: Activity): Result<Unit> = runCatching {
        val fb = auth ?: error("Firebase is not set up. Add app/google-services.json (see README).")
        val googleToken = googleIdToken(activity)
        fb.signInWithCredential(GoogleAuthProvider.getCredential(googleToken, null)).await()
        Unit
    }

    /** Forgot PIN / pattern (plan v3.3 section 3): proves again that this is the SAME Google account. Firebase refuses another
     *  account (it never switches the signed-in user), and the fresh token carries a new sign-in time the server checks. */
    suspend fun reauthenticateWithGoogle(activity: Activity): Result<Unit> = runCatching {
        val user = auth?.currentUser ?: error("Not signed in")
        val googleToken = googleIdToken(activity)
        user.reauthenticate(GoogleAuthProvider.getCredential(googleToken, null)).await()
        user.getIdToken(true).await()          // force a new ID token with the new sign-in time
        Unit
    }

    fun signOut() { auth?.signOut() }
}
