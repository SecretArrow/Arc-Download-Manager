package com.arc.downloadmanager.engine

import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.domain.model.DownloadSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings radio (Rename / Skip / Replace) is the single source of truth for filename
 * collisions — these tests pin the behavior the user sees:
 *  - RENAME never destroys anything and never asks: fresh "name (1).ext" copy.
 *  - REPLACE overwrites.
 *  - SKIP asks via the CONFLICT dialog (or skips re-added URLs at enqueue).
 *  - A restarted task always replaces its OWN previous artifact.
 */
class CollisionPolicyTest {
    // region finalizeDecision

    @Test
    fun `rename policy keeps the old file and saves a fresh copy`() {
        val decision =
            CollisionPolicy.finalizeDecision(
                policy = OverwritePolicy.RENAME,
                legacyAutoRename = false,
                destinationTaken = true,
            )
        assertEquals(CollisionPolicy.Finalize.RENAME_NEW, decision)
    }

    @Test
    fun `rename policy publishes directly when the name is free`() {
        val decision =
            CollisionPolicy.finalizeDecision(
                policy = OverwritePolicy.RENAME,
                legacyAutoRename = false,
                destinationTaken = false,
            )
        assertEquals(CollisionPolicy.Finalize.RENAME_NEW, decision)
    }

    @Test
    fun `replace policy overwrites a taken name without asking`() {
        val decision =
            CollisionPolicy.finalizeDecision(
                policy = OverwritePolicy.REPLACE,
                legacyAutoRename = false,
                destinationTaken = true,
            )
        assertEquals(CollisionPolicy.Finalize.OVERWRITE, decision)
    }

    @Test
    fun `skip policy parks in conflict only when the name is taken`() {
        val ask =
            CollisionPolicy.finalizeDecision(
                policy = OverwritePolicy.SKIP,
                legacyAutoRename = false,
                destinationTaken = true,
            )
        val publish =
            CollisionPolicy.finalizeDecision(
                policy = OverwritePolicy.SKIP,
                legacyAutoRename = false,
                destinationTaken = false,
            )
        assertEquals(CollisionPolicy.Finalize.ASK, ask)
        assertEquals(CollisionPolicy.Finalize.RENAME_NEW, publish)
    }

    @Test
    fun `legacy auto-rename switch keeps the silent fresh-copy behavior`() {
        val decision =
            CollisionPolicy.finalizeDecision(
                policy = OverwritePolicy.SKIP,
                legacyAutoRename = true,
                destinationTaken = true,
            )
        assertEquals(CollisionPolicy.Finalize.RENAME_NEW, decision)
    }

    @Test
    fun `restart replaces its own previous artifact under every policy`() {
        OverwritePolicy.entries.forEach { policy ->
            val decision =
                CollisionPolicy.finalizeDecision(
                    policy = policy,
                    legacyAutoRename = false,
                    destinationTaken = true,
                    replaceOwnArtifact = true,
                )
            assertEquals(CollisionPolicy.Finalize.OVERWRITE, decision)
        }
    }

    // endregion

    // region enqueueDecision

    @Test
    fun `skip policy ignores a re-added url from manual and batch sources`() {
        listOf(DownloadSource.MANUAL, DownloadSource.BATCH).forEach { source ->
            val decision =
                CollisionPolicy.enqueueDecision(
                    policy = OverwritePolicy.SKIP,
                    sameUrlTaskExists = true,
                    source = source,
                )
            assertEquals(CollisionPolicy.Enqueue.SKIP_DUPLICATE, decision)
        }
    }

    @Test
    fun `browser shares always download even under skip policy`() {
        val decision =
            CollisionPolicy.enqueueDecision(
                policy = OverwritePolicy.SKIP,
                sameUrlTaskExists = true,
                source = DownloadSource.BROWSER,
            )
        assertEquals(CollisionPolicy.Enqueue.ADD, decision)
    }

    @Test
    fun `rename and replace policies enqueue silently without asking`() {
        listOf(OverwritePolicy.RENAME, OverwritePolicy.REPLACE).forEach { policy ->
            val decision =
                CollisionPolicy.enqueueDecision(
                    policy = policy,
                    sameUrlTaskExists = true,
                    source = DownloadSource.MANUAL,
                )
            assertEquals(CollisionPolicy.Enqueue.ADD, decision)
        }
    }

    @Test
    fun `no existing task means always add`() {
        val decision =
            CollisionPolicy.enqueueDecision(
                policy = OverwritePolicy.SKIP,
                sameUrlTaskExists = false,
                source = DownloadSource.MANUAL,
            )
        assertEquals(CollisionPolicy.Enqueue.ADD, decision)
    }

    // endregion
}
