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
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Step 57 (plan v3.10.0 section 4): what a forced token refresh tells about the saved session. */
enum class SessionCheck {
    Valid,      // Firebase accepts the saved session: the account stays signed in
    Invalid,    // Firebase says the account is disabled / deleted / its session was revoked: sign-in is genuinely needed again
    Offline     // the check could not be made (no network, timeout ...): NOT a reason to sign anybody out
}

/** Google sign-in through Firebase Auth. Needs app/google-services.json. */
class AuthRepository(private val context: Context) {

    private val auth: FirebaseAuth? by lazy {
        if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance()
    }

    val isConfigured: Boolean get() = auth != null
    val isSignedIn: Boolean get() = auth?.currentUser != null

    /** Step 54: the stable Firebase id of the signed-in account (null = signed out). It is how the phone knows WHOSE App Lock it holds. */
    val uid: String? get() = auth?.currentUser?.uid

    suspend fun idToken(): String? = auth?.currentUser?.getIdToken(false)?.await()?.token

    /**
     * Step 57 (plan v3.10.0 section 5): ONE answer to "is somebody signed in?" at start-up. Firebase restores the saved session from the
     * phone's storage a moment after the app starts, so reading `currentUser` at once can wrongly say "nobody" and send a signed-in person
     * to the login screen. This waits for Firebase's own first answer (normally a few milliseconds; at most [timeoutMs]).
     */
    suspend fun awaitSession(timeoutMs: Long = 3_000): Boolean {
        val fb = auth ?: return false
        if (fb.currentUser != null) return true
        val answered = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val holder = arrayOfNulls<FirebaseAuth.AuthStateListener>(1)
                val listener = FirebaseAuth.AuthStateListener { a ->
                    holder[0]?.let { a.removeAuthStateListener(it) }
                    if (cont.isActive) cont.resume(a.currentUser != null)
                }
                holder[0] = listener
                fb.addAuthStateListener(listener)
                cont.invokeOnCancellation { holder[0]?.let { fb.removeAuthStateListener(it) } }
            }
        }
        return answered ?: (fb.currentUser != null)
    }

    // true while the app itself is signing out (Log out / Delete account / an invalid session), so that the observer below stays quiet
    @Volatile private var signingOutByApp = false

    /**
     * Step 57: observes the Firebase session while the app is open. [onEnded] is called ONLY when Firebase ends the session by itself
     * (account disabled or deleted, session revoked on another device): never for a normal Log out, a restart or a network problem.
     * Returns a function that stops observing.
     */
    fun observeEnded(onEnded: () -> Unit): () -> Unit {
        val fb = auth ?: return {}
        var hadUser = fb.currentUser != null
        val listener = FirebaseAuth.AuthStateListener { a ->
            val has = a.currentUser != null
            if (hadUser && !has && !signingOutByApp) onEnded()
            hadUser = has
            if (has) signingOutByApp = false
        }
        fb.addAuthStateListener(listener)
        return { fb.removeAuthStateListener(listener) }
    }

    /** Step 57: asks Firebase for a brand-new token once, so an old token is never mistaken for an ended session. */
    suspend fun refreshSession(): SessionCheck {
        val user = auth?.currentUser ?: return SessionCheck.Invalid
        return try {
            user.getIdToken(true).await()
            SessionCheck.Valid
        } catch (e: CancellationException) { throw e }
        catch (e: FirebaseAuthInvalidUserException) { SessionCheck.Invalid }          // disabled / deleted / token revoked
        catch (e: FirebaseAuthInvalidCredentialsException) { SessionCheck.Invalid }
        catch (e: Exception) { SessionCheck.Offline }                                  // network trouble and everything unknown: keep the session
    }

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

    /** Step 54 (plan v3.9.2): log out ONLY ends this session. It never deletes the account or any saved data (that is a separate,
     *  confirmed action: Settings > Delete account). */
    fun signOut() { signingOutByApp = true; auth?.signOut() }
}
