package com.dshbox.app.sandbox

import com.dshbox.app.common.AppResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [DefaultSandboxManager] — the core sandbox state machine.
 *
 * Strategy:
 * - [TemporaryFolder] provides a realistic filesystem (runtime slots, rootfs).
 * - [SandboxProcessRunner] and [BundleManager] are mocked (they touch Android
 *   APIs / heavyweight IO). The manager accepts them via constructor.
 * - The health loop is exercised on an injected test scope, so virtual-time
 *   control (UnconfinedTestDispatcher + advanceUntilIdle) keeps tests fast and
 *   deterministic; the timeout path runs on the real IO scope with a bounded
 *   wall-clock wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSandboxManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    /** Path of the mock proot binary (returned via nativeLibraryDir). */
    private lateinit var prootBinary: File

    /** The runtime-current/debian directory the manager expects. */
    private lateinit var debianRootfs: File

    private lateinit var config: SandboxConfig
    private lateinit var processRunner: SandboxProcessRunner
    private lateinit var bundleManager: BundleManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        prootBinary = tmp.newFile("libproot.so").also { it.writeText("mock") }

        val appFilesDir = tmp.newFolder("appfiles")
        debianRootfs = File(appFilesDir, "runtime/runtime-current/debian").apply { mkdirs() }

        config = SandboxConfig(
            appFilesDir = appFilesDir,
            nativeLibraryDir = tmp.root.absolutePath,
            dshReadyTimeoutMs = 500L,
            healthCheckTimeoutMs = 100L,
        )

        processRunner = mockk(relaxed = true)
        bundleManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Manager with a test-scope health loop (virtual time). */
    private fun newManager(
        healthChecker: SandboxHealthChecker = FakeHealthChecker.neverReady(),
    ): DefaultSandboxManager = DefaultSandboxManager(
        config = config,
        healthChecker = healthChecker,
        processRunner = processRunner,
        bundleManager = bundleManager,
        scope = CoroutineScope(SupervisorJob() + testDispatcher),
    )

    /** Mocks a successful proot launch (build + start). */
    private fun mockSuccessfulLaunch() {
        coEvery { processRunner.buildProotStartCommand(any(), any(), any()) } returns listOf("/mock/proot")
        coEvery { processRunner.start(any(), any(), any(), any()) } returns SandboxProcessRunner.RunningProcess(
            process = mockProcess(),
            tag = "proot",
        )
    }

    // ─── State Machine: Initialization ─────────────────────────────────────────

    @Test
    fun `initial state is UNINITIALIZED`() {
        assertEquals(SandboxState.UNINITIALIZED, newManager().state.value)
    }

    @Test
    fun `initialize transitions to STOPPED and creates all directories`() = runTest {
        val manager = newManager()
        manager.initialize()

        assertEquals(SandboxState.STOPPED, manager.state.value)
        assertTrue("runtime dir", config.runtimeDir.exists())
        assertTrue("sandbox dir", config.sandboxDir.exists())
        assertTrue("user-data dir", config.userDataDir.exists())
        assertTrue("logs dir", config.logsDir.exists())
        assertTrue("backups dir", config.backupsDir.exists())
        assertTrue("updates dir", config.updatesDir.exists())
    }

    @Test
    fun `initialize is idempotent after first call`() = runTest {
        val manager = newManager()
        manager.initialize()
        manager.initialize()
        assertEquals(SandboxState.STOPPED, manager.state.value)
    }

    // ─── State Machine: Start / Stop / Restart ─────────────────────────────────

    @Test
    fun `start launches proot and rewrites resolv conf`() = runTest {
        val manager = newManager()
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start()

        assertEquals(SandboxState.RUNNING, manager.state.value)
        verify { processRunner.buildProotStartCommand(any(), any(), any()) }
        verify { processRunner.start(any(), any(), any(), any()) }
        // Missing resolv.conf -> rewritten with public resolvers.
        val resolv = File(debianRootfs, "etc/resolv.conf")
        assertTrue("resolv.conf rewritten", resolv.readText().contains("nameserver 114.114.114.114"))
    }

    @Test
    fun `start is a no-op when already running`() = runTest {
        val manager = newManager()
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start()
        manager.start()

        assertEquals(SandboxState.RUNNING, manager.state.value)
        verify(exactly = 1) { processRunner.start(any(), any(), any(), any()) }
    }

    @Test
    fun `stop reaches STOPPED and stops the process`() = runTest {
        val manager = newManager()
        manager.initialize()
        mockSuccessfulLaunch()
        manager.start()
        assertEquals(SandboxState.RUNNING, manager.state.value)

        manager.stop()

        assertEquals(SandboxState.STOPPED, manager.state.value)
        verify { processRunner.stop(any()) }
    }

    @Test
    fun `stop from UNINITIALIZED is safe`() = runTest {
        val manager = newManager()
        manager.stop()
        assertEquals(SandboxState.STOPPED, manager.state.value)
    }

    @Test
    fun `restart stops then starts and ends RUNNING`() = runTest {
        val manager = newManager()
        manager.initialize()
        mockSuccessfulLaunch()
        manager.start()
        assertEquals(SandboxState.RUNNING, manager.state.value)

        val job = launch { manager.restart() }
        job.join()

        assertEquals(SandboxState.RUNNING, manager.state.value)
        verify(exactly = 1) { processRunner.stop(any()) }
        verify(exactly = 2) { processRunner.start(any(), any(), any(), any()) }
    }

    // ─── Health Loop ───────────────────────────────────────────────────────────

    @Test
    fun `health loop reaches READY once WebUI answers`() = runTest {
        val healthChecker = FakeHealthChecker(readyAfterChecks = 1)
        val manager = newManager(healthChecker = healthChecker)
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start() // starts the health loop on the test scope
        testDispatcher.scheduler.advanceUntilIdle() // run bounce + ready check

        assertEquals(SandboxState.READY, manager.state.value)
    }

    @Test
    fun `health loop transitions to ERROR when startup times out`() = runTest {
        config = config.copy(dshReadyTimeoutMs = 200L)
        // Real IO scope on purpose: the timeout branch compares against
        // System.currentTimeMillis(), which virtual time can not advance.
        val manager = DefaultSandboxManager(
            config = config,
            healthChecker = FakeHealthChecker.neverReady(),
            processRunner = processRunner,
            bundleManager = bundleManager,
        )
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start()

        // Bound the wall-clock wait; interval is 2s default, so allow ~5s.
        val deadline = System.currentTimeMillis() + 6_000
        while (manager.state.value != SandboxState.ERROR && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertEquals(SandboxState.ERROR, manager.state.value)
    }

    @Test
    fun `health loop auto-restarts once after a post-ready failure and bounds via supervisor cap`() = runTest {
        // Sequence: ready, fail, then three consecutive failures (supervisor cap).
        // L1: ready → fail → budget=1 → auto-restart
        // L2 (pre-ready): fail × 3 → supervisor ERROR → state=ERROR
        val healthChecker = ScriptedHealthChecker(true, false, false, false, false)
        val manager = newManager(healthChecker = healthChecker)
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SandboxState.ERROR, manager.state.value)
        // 1 initial start + 1 auto-restart
        verify(exactly = 2) { processRunner.start(any(), any(), any(), any()) }
        verify(exactly = 1) { processRunner.stop(any()) }
    }

    @Test
    fun `health loop bounds flapping post-ready cycles and enters ERROR`() = runTest {
        // Sequence: ready, fail, ready, fail, ready, fail → budget 1,2,3 → ERROR
        val healthChecker = ScriptedHealthChecker(true, false, true, false, true, false)
        val manager = newManager(healthChecker = healthChecker)
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SandboxState.ERROR, manager.state.value)
        // 1 initial + 2 auto-restarts (third failure hits cap, no restart)
        verify(exactly = 3) { processRunner.start(any(), any(), any(), any()) }
        verify(exactly = 2) { processRunner.stop(any()) }
    }

    @Test
    fun `health loop auto-restarts after a post-ready failure then recovers`() = runTest {
        // Sequence: ready, fail, ready, ready, fail, fail, fail (supervisor cap)
        // L1: ready → fail → budget=1 → auto-restart
        // L2: ready → ready → fail → budget=2 → auto-restart
        // L3 (pre-ready): fail × 3 → supervisor ERROR
        // This proves two bounded auto-restarts occurred with recovery in between.
        val healthChecker = ScriptedHealthChecker(true, false, true, true, false, false, false)
        val manager = newManager(healthChecker = healthChecker)
        manager.initialize()
        mockSuccessfulLaunch()

        manager.start()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SandboxState.ERROR, manager.state.value)
        // 1 initial + 2 auto-restarts
        verify(exactly = 3) { processRunner.start(any(), any(), any(), any()) }
        verify(exactly = 2) { processRunner.stop(any()) }
    }

    // ─── Error Handling ────────────────────────────────────────────────────────

    @Test
    fun `start fails gracefully to ERROR when runtime is missing`() = runTest {
        config = SandboxConfig(
            appFilesDir = tmp.newFolder("empty-app"),
            dshReadyTimeoutMs = 100L,
            healthCheckTimeoutMs = 100L,
        )
        val manager = newManager()
        manager.initialize()

        manager.start() // ensureRuntimePresent() fails -> ERROR, no throw

        assertEquals(SandboxState.ERROR, manager.state.value)
        verify(exactly = 0) { processRunner.start(any(), any(), any(), any()) }
    }

    @Test
    fun `start catches process launch failure and reaches ERROR`() = runTest {
        val manager = newManager()
        manager.initialize()
        coEvery { processRunner.buildProotStartCommand(any(), any(), any()) } returns listOf("/mock/proot")
        coEvery { processRunner.start(any(), any(), any(), any()) } throws RuntimeException("launch failed")

        manager.start()

        assertEquals(SandboxState.ERROR, manager.state.value)
    }

    // ─── Runtime Bundle Management ─────────────────────────────────────────────

    @Test
    fun `installRuntimeBundle rejected while sandbox is running`() = runTest {
        val manager = newManager()
        manager.initialize()
        mockSuccessfulLaunch()
        manager.start()

        val result = manager.installRuntimeBundle(File("test.tar.gz"), "abcdef")

        assertTrue(result is AppResult.Failure)
        verify(exactly = 0) { bundleManager.installToNewSlot(any(), any()) }
    }

    @Test
    fun `installRuntimeBundle delegates to bundleManager when stopped`() = runTest {
        val manager = newManager()
        manager.initialize()
        val bundle = tmp.newFile("test.tar.gz")
        coEvery { bundleManager.installToNewSlot(bundle, "valid-hash") } returns AppResult.Success(bundle)

        val result = manager.installRuntimeBundle(bundle, "valid-hash")

        assertTrue(result is AppResult.Success)
        verify { bundleManager.installToNewSlot(bundle, "valid-hash") }
    }

    @Test
    fun `promoteRuntimeBundle delegates to bundleManager when stopped`() = runTest {
        val manager = newManager()
        manager.initialize()
        coEvery { bundleManager.promoteNewSlotToCurrent() } returns AppResult.Success(Unit)

        val result = manager.promoteRuntimeBundle()

        assertTrue(result is AppResult.Success)
        verify { bundleManager.promoteNewSlotToCurrent() }
    }

    @Test
    fun `rollbackRuntime stops sandbox first then delegates`() = runTest {
        val manager = newManager()
        manager.initialize()
        mockSuccessfulLaunch()
        manager.start()
        coEvery { bundleManager.rollback() } returns AppResult.Success(Unit)

        val result = manager.rollbackRuntime()

        assertTrue(result is AppResult.Success)
        verify { processRunner.stop(any()) }
        verify { bundleManager.rollback() }
    }

    // ─── Recovery Levels ───────────────────────────────────────────────────────

    @Test
    fun `recover DSH_RESTART uses light path when DSH comes back`() = runTest {
        val healthChecker = FakeHealthChecker(readyAfterChecks = 0, initialReady = true)
        val manager = newManager(healthChecker = healthChecker)
        manager.initialize()
        mockSuccessfulLaunch()
        manager.start()

        val result = manager.recover(RecoveryLevel.DSH_RESTART)

        assertTrue(result is AppResult.Success)
        verify { processRunner.stopDsh() }
    }

    @Test
    fun `recover SANDBOX_RESTART performs full restart`() = runTest {
        val healthChecker = FakeHealthChecker(readyAfterChecks = 0, initialReady = true)
        val manager = newManager(healthChecker = healthChecker)
        manager.initialize()
        mockSuccessfulLaunch()
        manager.start()

        val result = manager.recover(RecoveryLevel.SANDBOX_RESTART)

        assertTrue(result is AppResult.Success)
        verify(exactly = 1) { processRunner.stop(any()) }
    }

    // ─── isRuntimeInstalled ────────────────────────────────────────────────────

    @Test
    fun `isRuntimeInstalled true when proot binary and rootfs exist`() {
        assertTrue(newManager().isRuntimeInstalled())
    }

    @Test
    fun `isRuntimeInstalled false when proot binary missing`() {
        prootBinary.delete()
        assertFalse(newManager().isRuntimeInstalled())
    }

    @Test
    fun `isRuntimeInstalled false when rootfs missing`() {
        debianRootfs.delete()
        assertFalse(newManager().isRuntimeInstalled())
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** A mock [Process] that behaves like a live process. */
    private fun mockProcess(): Process = mockk {
        every { isAlive } returns true
        every { inputStream } returns java.io.ByteArrayInputStream(ByteArray(0))
        every { errorStream } returns java.io.ByteArrayInputStream(ByteArray(0))
        every { outputStream } returns java.io.ByteArrayOutputStream()
        every { exitValue() } throws IllegalThreadStateException("still running")
        every { waitFor() } returns 0
        every { destroy() } returns Unit
    }

    /**
     * Fake [SandboxHealthChecker]. Reports ready once [readyAfterChecks] checks
     * have been performed (or immediately when [initialReady] is true).
     */
    private class FakeHealthChecker(
        private val readyAfterChecks: Int = Int.MAX_VALUE,
        private val initialReady: Boolean = false,
    ) : SandboxHealthChecker {
        private val lock = Object()
        private var checkCount = 0
        private var ready = initialReady

        override suspend fun check(): SandboxHealth {
            synchronized(lock) {
                if (checkCount >= readyAfterChecks) ready = true
                checkCount++
            }
            val isReady = synchronized(lock) { ready }
            return SandboxHealth(
                sandboxState = if (isReady) SandboxState.READY else SandboxState.RUNNING,
                dshProcessRunning = true,
                portOpen = isReady,
                webUiReady = isReady,
            )
        }

        companion object {
            fun neverReady(): FakeHealthChecker = FakeHealthChecker(readyAfterChecks = Int.MAX_VALUE)
        }
    }

    /**
     * Scripted [SandboxHealthChecker]. Returns `ready` for [script] in order,
     * then repeats the last element for every further check. Useful for
     * deterministic crash-loop / flap scenarios that must terminate in ERROR.
     */
    private class ScriptedHealthChecker(vararg script: Boolean) : SandboxHealthChecker {
        private val script: List<Boolean> = script.toList()
        private val lock = Object()
        private var index = 0

        override suspend fun check(): SandboxHealth {
            val ready = synchronized(lock) {
                val value = script.getOrElse(index) { script.last() }
                index++
                value
            }
            return SandboxHealth(
                sandboxState = if (ready) SandboxState.READY else SandboxState.RUNNING,
                dshProcessRunning = true,
                portOpen = ready,
                webUiReady = ready,
            )
        }
    }
}