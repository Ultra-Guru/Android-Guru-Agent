package com.apex.agent.platform

import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.runtime.BackendRuntimeType
import com.apex.agent.platform.terminal.runtime.ExecutionBackend
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The updater's backend bridge is exercised without Android accessibility/service fakes. */
class ChrootAvailabilityTelemetryTest {
    private class Backend(var value: BackendAvailability) : ExecutionBackend {
        override val id = "linux-ubuntu-chroot"
        override val runtimeType = BackendRuntimeType.LINUX
        var lookups = 0
        override suspend fun availability(): BackendAvailability { lookups++; return value }
        override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> =
            error("telemetry must not spawn sessions or execute su")
    }

    @Test fun `ready is visible and missing su is explicitly off`() = runTest {
        val state = ToolEnvironmentState()
        val backend = Backend(BackendAvailability.Ready)
        refreshChrootAvailability(backend, state)
        assertEquals(true, state.get(ToolEnvironmentState.Flags.CHROOT_READY))
        assertTrue(state.summary().contains("chroot_ready=on"))

        backend.value = BackendAvailability.Failed("ChrootError:SU_NOT_FOUND — no su")
        refreshChrootAvailability(backend, state)
        assertEquals(false, state.get(ToolEnvironmentState.Flags.CHROOT_READY))
        assertTrue(state.summary().contains("chroot_ready=off"))
        assertEquals(2, backend.lookups)
    }

    @Test fun `rootfs pending and authorization unknown clear stale telemetry instead of denying`() = runTest {
        val state = ToolEnvironmentState()
        val backend = Backend(BackendAvailability.Ready)
        refreshChrootAvailability(backend, state)
        backend.value = BackendAvailability.NeedsRootfs()
        refreshChrootAvailability(backend, state)
        assertNull(state.get(ToolEnvironmentState.Flags.CHROOT_READY))
        assertFalse(state.summary().contains("chroot_ready="))

        backend.value = BackendAvailability.Failed("ChrootError:SU_NOT_AUTHORIZED — grant access")
        refreshChrootAvailability(backend, state)
        assertNull(state.get(ToolEnvironmentState.Flags.CHROOT_READY))
    }

    @Test fun `missing backend and unexpected failure remain unknown`() = runTest {
        val state = ToolEnvironmentState()
        state.set(ToolEnvironmentState.Flags.CHROOT_READY, true)
        refreshChrootAvailability(null, state)
        assertNull(state.get(ToolEnvironmentState.Flags.CHROOT_READY))

        val broken = object : ExecutionBackend {
            override val id = "linux-ubuntu-chroot"
            override val runtimeType = BackendRuntimeType.LINUX
            override suspend fun availability(): BackendAvailability = throw IllegalStateException("broken probe")
            override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> = error("unused")
        }
        refreshChrootAvailability(broken, state)
        assertNull(state.get(ToolEnvironmentState.Flags.CHROOT_READY))
    }
}
