package com.arc.downloadmanager

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.arc.downloadmanager.data.repository.AddResult
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.domain.model.DownloadSource
import com.arc.downloadmanager.util.LinkText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Invisible pass-through target for the system share sheet: receives ACTION_SEND text from
 * any app, enqueues the first link found in it and finishes immediately. The download engine
 * is started by [ArcApp.onCreate], so a link shared here is queued without MainActivity ever
 * opening; the translucent theme keeps the user's app visible throughout.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject
    lateinit var repository: DownloadRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = LinkText.firstUrl(intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            showToast(R.string.share_no_link)
            finish()
            return
        }
        lifecycleScope.launch {
            val messageRes =
                when (repository.add(DownloadRequest(url = url, source = DownloadSource.MANUAL))) {
                    is AddResult.Started -> R.string.share_added
                    is AddResult.Duplicate -> R.string.share_duplicate
                    is AddResult.Invalid -> R.string.share_invalid
                }
            showToast(messageRes)
            finish()
        }
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }
}
