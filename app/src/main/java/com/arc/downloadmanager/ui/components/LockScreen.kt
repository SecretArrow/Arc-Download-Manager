package com.arc.downloadmanager.ui.components

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.arc.downloadmanager.R
import com.arc.downloadmanager.ui.theme.ArcHeroBrush

/**
 * Shows the Arc unlock prompt (biometric or device credential).
 *
 * Authenticator policy:
 *  - API 30+: `BIOMETRIC_WEAK` or the device credential (PIN/pattern/password).
 *  - API < 30: `BIOMETRIC_WEAK` only — combining it with `DEVICE_CREDENTIAL`
 *    throws on these APIs, so the classic fingerprint prompt with a "Cancel"
 *    negative button is used instead.
 *
 * Before presenting, [BiometricManager.canAuthenticate] is probed with the same
 * authenticator set the prompt would use. Returns `true` when the prompt was
 * presented, `false` when no usable authenticator exists (no hardware or
 * nothing enrolled). Callers MUST handle `false` so users can never be locked
 * out for good — the lock screen offers a session-only "Unlock anyway" escape
 * hatch in that case, and Settings leaves the switch off.
 */
fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (errorCode: Int, message: CharSequence) -> Unit,
): Boolean {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuthenticate =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.from(activity).canAuthenticate(authenticators)
        } else {
            // API < 30: the combined set is unsupported, probe plain biometrics.
            BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
    if (canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
        canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    ) {
        return false
    }

    val promptInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_title))
                .setAllowedAuthenticators(authenticators)
                .build()
        } else {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_title))
                .setNegativeButtonText(activity.getString(R.string.action_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        }

    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    onError(errorCode, errString)
                }
            },
        )
    prompt.authenticate(promptInfo)
    return true
}

/**
 * Full-screen gate shown instead of the app while app lock is enabled and the
 * session is locked. Brand tile reuses the hero gradient ([ArcHeroBrush]) with
 * the white-tinted logo mark (the raw vector strokes are white, same treatment
 * logic as the Settings about card). [fallback] renders the "no authenticator"
 * escape hatch: an explanation plus a session-only unlock button.
 */
@Composable
fun LockScreen(
    fallback: Boolean,
    onUnlock: () -> Unit,
    onUnlockAnyway: () -> Unit,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(ArcHeroBrush),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            if (fallback) {
                // No usable authenticator on this device (no hardware / nothing
                // enrolled). Explain it and offer a session-only unlock so the
                // user is never permanently locked out.
                Text(
                    stringResource(R.string.lock_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onUnlockAnyway) {
                    Text(stringResource(R.string.lock_unlock_anyway))
                }
            } else {
                Button(onClick = onUnlock) {
                    Text(stringResource(R.string.lock_unlock))
                }
            }
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
