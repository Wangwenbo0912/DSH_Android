package com.dshbox.app.workspace

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * Writes the workspace record into the DSH JSON storage file
 * (`{userDataDir}/.dsh/storages/workspace.json`) following the dsh-storage-json
 * unit format (unit header + global singleton + `workspaces` table), so the DSH
 * WebUI workspace registry discovers the selected directory on its next restart.
 *
 * Kept as a standalone object (no Android Context dependency) so the format can
 * be unit-tested on the JVM; [WorkspaceManager] delegates to it.
 */
internal object DshWorkspaceStorage {

    private const val TAG = "DshWorkspaceStorage"

    /** DSH workspace domain name (doubles as the JSON unit file name). */
    private const val DOMAIN_NAME = "workspace"
    /** Current workspace domain format version (must match dsh-workspace). */
    private const val DOMAIN_VERSION = 2
    /** DSH storage root relative to userDataDir. */
    private const val STORAGE_RELATIVE = ".dsh/storages"

    /**
     * Write or update a workspace record in the DSH workspace.json storage file
     * so that the DSH WebUI workspace registry discovers the directory on its
     * next restart.
     *
     * The method is idempotent: if a record for the same guest path already
     * exists in the storage file, its `updatedAt` timestamp is refreshed and
     * the workspace is promoted to the front of the registry order. If the file
     * already exists with a different domain version, the write is skipped to
     * avoid downgrading the format.
     *
     * @param guestPath  Guest-format path, e.g. "/root/projects/my-project".
     * @param userDataDir  Host-side user data directory
     * ([com.dshbox.app.sandbox.SandboxConfig.userDataDir]).
     */
    suspend fun ensureWorkspaceInStorage(guestPath: String, userDataDir: File) {
        withContext(Dispatchers.IO) {
            val storageDir = File(userDataDir, STORAGE_RELATIVE)
            storageDir.mkdirs()
            val storageFile = File(storageDir, "$DOMAIN_NAME.json")

            // Read existing file if it exists and is valid.
            val existingDoc = try {
                if (storageFile.isFile) {
                    JSONObject(storageFile.readText())
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "existing workspace.json malformed, will overwrite", e)
                null
            }

            // If the file belongs to a different domain version, skip to avoid
            // downgrading the format (DSH's parse() would reject the mismatch).
            if (existingDoc != null) {
                val unit = existingDoc.optJSONObject("unit")
                if (unit != null && unit.optInt("version", 0) != DOMAIN_VERSION) {
                    Log.w(TAG, "workspace.json has version ${unit.optInt("version")} " +
                        "!= expected $DOMAIN_VERSION, skipping write")
                    return@withContext
                }
            }

            val document = existingDoc ?: freshDocument()
            val global = document.optJSONObject("global") ?: JSONObject()
            val tables = document.optJSONObject("tables") ?: JSONObject()
            val workspaces = tables.optJSONObject("workspaces") ?: JSONObject()

            val now = Instant.now().toString()
            val title = guestPath.trimEnd('/').substringAfterLast('/')

            // Find existing record by path (paths are unique per registry spec).
            var existingId: String? = null
            for (key in workspaces.keys()) {
                val ws = workspaces.optJSONObject(key) ?: continue
                if (ws.optString("path", "") == guestPath) {
                    existingId = key
                    break
                }
            }

            val wsId = existingId ?: UUID.randomUUID().toString()
            val workspaceRecord = if (existingId != null) {
                workspaces.getJSONObject(existingId).apply {
                    put("updatedAt", now)
                }
            } else {
                JSONObject().apply {
                    put("path", guestPath)
                    put("title", title)
                    put("sessionIds", JSONArray())
                    put("createdAt", now)
                    put("updatedAt", now)
                }
            }

            if (existingId == null) {
                workspaces.put(wsId, workspaceRecord)
            }

            // Build workspaceIds order: promote the selected workspace to front.
            val ids = mutableListOf<String>()
            val idsArray = global.optJSONArray("workspaceIds") ?: JSONArray()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.getString(i))
            }
            ids.remove(wsId)
            ids.add(0, wsId)

            global.put("initialized", true)
            global.put("workspaceIds", JSONArray(ids))
            if (!global.has("archivedSessionIds")) {
                global.put("archivedSessionIds", JSONArray())
            }

            // Assemble the full document.
            document.put("unit", JSONObject().apply {
                put("name", DOMAIN_NAME)
                put("version", DOMAIN_VERSION)
            })
            document.put("global", global)
            tables.put("workspaces", workspaces)
            document.put("tables", tables)

            // Atomic write: temp file → Files.move (atomic replace via
            // REPLACE_EXISTING; reliable on all platforms including Windows).
            val tmp = File(storageDir, ".${UUID.randomUUID()}.tmp")
            try {
                tmp.writeText(document.toString(2) + "\n")
                Files.move(
                    tmp.toPath(),
                    storageFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                Log.i(TAG, "workspace record written: $guestPath (id=$wsId)")
            } catch (e: IOException) {
                // ATOMIC_MOVE may not be supported on all filesystems (e.g.
                // FAT32, some network mounts). Retry without the atomic flag.
                try {
                    Files.move(tmp.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Log.i(TAG, "workspace record written (non-atomic): $guestPath (id=$wsId)")
                } catch (e2: Exception) {
                    tmp.delete()
                    Log.w(TAG, "failed to write workspace record", e2)
                    throw e2
                }
            }
        }
    }

    /** Create a minimal valid workspace.json document without any records. */
    private fun freshDocument(): JSONObject = JSONObject().apply {
        put("unit", JSONObject().apply {
            put("name", DOMAIN_NAME)
            put("version", DOMAIN_VERSION)
        })
        put("global", JSONObject().apply {
            put("initialized", false)
            put("workspaceIds", JSONArray())
            put("archivedSessionIds", JSONArray())
        })
        put("tables", JSONObject().apply {
            put("workspaces", JSONObject())
        })
    }
}