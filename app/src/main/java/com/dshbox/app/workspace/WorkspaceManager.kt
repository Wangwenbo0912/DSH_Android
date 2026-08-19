package com.dshbox.app.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persists and exposes the current workspace path.
 *
 * The workspace path is stored in SharedPreferences as a guest-format path
 * (e.g. "/root/projects/my-project") and exposed as a [StateFlow] so that
 * Compose UI can observe changes reactively.
 *
 * Workspace selection is also written into the DSH JSON storage registry via
 * [ensureWorkspaceInStorage] so the DSH WebUI workspace list discovers the
 * chosen directory (see [DshWorkspaceStorage] for the storage format).
 *
 * Default: `null` (no explicit workspace selected; the bridge uses its own
 * default of "/root/projects").
 */
class WorkspaceManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentWorkspacePath = MutableStateFlow<String?>(
        prefs.getString(KEY_WORKSPACE_PATH, null)
    )

    /** Observable current workspace path (guest format, e.g. "/root/projects/..."). */
    val currentWorkspacePath: StateFlow<String?> = _currentWorkspacePath.asStateFlow()

    /**
     * Persist a new workspace path and update the observable state.
     * Pass an empty string or a path that trims to empty to clear the selection.
     */
    fun setWorkspace(path: String) {
        val normalized = path.trimEnd('/').ifEmpty { null }
        prefs.edit()
            .putString(KEY_WORKSPACE_PATH, normalized)
            .apply()
        _currentWorkspacePath.value = normalized
    }

    /** Returns the current workspace path, or `null` if none is set. */
    fun currentWorkspace(): String? = _currentWorkspacePath.value

    /**
     * Write or update a workspace record in the DSH workspace.json storage file
     * so that the DSH WebUI workspace registry discovers the directory on its
     * next restart. Delegates to [DshWorkspaceStorage].
     *
     * @param guestPath  Guest-format path, e.g. "/root/projects/my-project".
     * @param userDataDir  Host-side user data directory ([com.dshbox.app.sandbox.SandboxConfig.userDataDir]).
     */
    suspend fun ensureWorkspaceInStorage(guestPath: String, userDataDir: File) {
        DshWorkspaceStorage.ensureWorkspaceInStorage(guestPath, userDataDir)
    }

    companion object {
        private const val PREFS_NAME = "dsh_workspace"
        private const val KEY_WORKSPACE_PATH = "current_workspace_path"
    }
}