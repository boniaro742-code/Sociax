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

    suspend fun signInWithGoogle(activity: Activity): Result<Unit> = runCatching {
        val fb = auth ?: error("Firebase is not set up. Add app/google-services.json (see README).")
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
        val googleToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
        fb.signInWithCredential(GoogleAuthProvider.getCredential(googleToken, null)).await()
        Unit
    }

    fun signOut() { auth?.signOut() }
}
