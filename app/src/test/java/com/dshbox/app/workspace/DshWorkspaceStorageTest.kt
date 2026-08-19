package com.dshbox.app.workspace

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [DshWorkspaceStorage] — verifies the workspace.json file
 * format matches the dsh-storage-json unit spec (unit header, global singleton,
 * workspaces table) that the DSH workspace registry opens on startup.
 *
 * Runs on JVM with the real org.json:json library; [DshWorkspaceStorage] uses
 * no Android Context, only File + org.json + kotlinx.coroutines, so no
 * Robolectric or mock is needed.
 */
class DshWorkspaceStorageTest {

    @Test
    fun `writes workspace record with correct structure`(): Unit = runBlocking {
        val tmpDir = createTempDir()
        try {
            val guestPath = "/root/projects/my-project"
            DshWorkspaceStorage.ensureWorkspaceInStorage(guestPath, tmpDir)

            val storageFile = storageFile(tmpDir)
            assertTrue("workspace.json should exist", storageFile.isFile)

            val doc = JSONObject(storageFile.readText())
            verifyUnitHeader(doc)
            verifyGlobal(doc, expectedIds = 1)
            verifyWorkspaceRecord(doc, guestPath, "my-project")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `is idempotent for same path`(): Unit = runBlocking {
        val tmpDir = createTempDir()
        try {
            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/foo", tmpDir)
            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/foo", tmpDir)

            val doc = JSONObject(storageFile(tmpDir).readText())
            val global = doc.getJSONObject("global")
            // Only one workspace id in the order.
            assertEquals(1, global.getJSONArray("workspaceIds").length())
            val workspaces = doc.getJSONObject("tables").getJSONObject("workspaces")
            assertEquals(1, workspaces.length())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `adds multiple distinct workspaces and reorders on select`(): Unit = runBlocking {
        val tmpDir = createTempDir()
        try {
            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/first", tmpDir)
            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/second", tmpDir)

            // The second write should prepend "second" to the order.
            val doc1 = JSONObject(storageFile(tmpDir).readText())
            val ids1 = doc1.getJSONObject("global").getJSONArray("workspaceIds")
            assertEquals(2, ids1.length())

            // Re-select the first workspace — it should be promoted to front.
            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/first", tmpDir)
            val doc2 = JSONObject(storageFile(tmpDir).readText())
            val ids2 = doc2.getJSONObject("global").getJSONArray("workspaceIds")
            assertEquals(2, ids2.length())
            // The first workspace id should now be at index 0.
            val workspaces = doc2.getJSONObject("tables").getJSONObject("workspaces")
            val firstId = ids2.getString(0)
            assertNotNull("first workspace should be promoted to front", firstId)
            assertEquals(
                "/root/projects/first",
                workspaces.getJSONObject(firstId).getString("path"),
            )
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `skips write when file has unknown version`(): Unit = runBlocking {
        val tmpDir = createTempDir()
        try {
            val storageDir = File(tmpDir, ".dsh/storages")
            storageDir.mkdirs()
            val storageFile = storageFile(tmpDir)

            // Write a file with a different version (3).
            storageFile.writeText(
                """
                {
                  "unit": { "name": "workspace", "version": 3 },
                  "global": { "initialized": true, "workspaceIds": [], "archivedSessionIds": [] },
                  "tables": { "workspaces": {} }
                }
                """.trimIndent() + "\n",
            )

            // The write should be skipped (no crash, no record added).
            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/skip-me", tmpDir)

            val doc = JSONObject(storageFile.readText())
            val unit = doc.getJSONObject("unit")
            assertEquals("version should remain 3", 3, unit.getInt("version"))
            val workspaces = doc.getJSONObject("tables").getJSONObject("workspaces")
            assertEquals("no record should be added", 0, workspaces.length())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `creates storage dir and file when absent`(): Unit = runBlocking {
        val tmpDir = createTempDir()
        try {
            // The storage directory does not exist yet.
            val storageDir = File(tmpDir, ".dsh/storages")
            assertTrue("storage dir should be created", !storageDir.exists())

            DshWorkspaceStorage.ensureWorkspaceInStorage("/root/projects/new", tmpDir)

            assertTrue("storage dir should exist after write", storageDir.isDirectory())
            assertTrue("workspace.json should exist", storageFile(tmpDir).isFile())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun storageFile(root: File): File =
        File(File(root, ".dsh/storages"), "workspace.json")

    private fun verifyUnitHeader(doc: JSONObject) {
        val unit = doc.getJSONObject("unit")
        assertEquals("workspace", unit.getString("name"))
        assertEquals(2, unit.getInt("version"))
    }

    private fun verifyGlobal(doc: JSONObject, expectedIds: Int) {
        val global = doc.getJSONObject("global")
        assertTrue("global.initialized should be true", global.getBoolean("initialized"))
        val ids = global.getJSONArray("workspaceIds")
        assertEquals(expectedIds, ids.length())
        assertNotNull("workspaceIds entry should be a string", ids.getString(0))
        assertTrue(
            "archivedSessionIds should be present",
            global.has("archivedSessionIds"),
        )
    }

    private fun verifyWorkspaceRecord(doc: JSONObject, expectedPath: String, expectedTitle: String) {
        val workspaces = doc.getJSONObject("tables").getJSONObject("workspaces")
        assertEquals(1, workspaces.length())

        val key = workspaces.keys().next()
        val record = workspaces.getJSONObject(key)
        assertEquals(expectedPath, record.getString("path"))
        assertEquals(expectedTitle, record.getString("title"))
        assertTrue(record.getJSONArray("sessionIds").length() == 0)
        assertNotNull(record.getString("createdAt"))
        assertNotNull(record.getString("updatedAt"))
    }

    private fun createTempDir(): File =
        Files.createTempDirectory("dsh-ws-test-").toFile()
}