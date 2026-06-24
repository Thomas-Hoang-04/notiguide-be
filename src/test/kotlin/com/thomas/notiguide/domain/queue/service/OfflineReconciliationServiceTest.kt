package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.domain.queue.request.OfflineAction
import com.thomas.notiguide.domain.queue.request.OfflineTransition
import com.thomas.notiguide.domain.queue.request.ReconcileOfflineRequest
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OfflineReconciliationServiceTest {
    private val queueService = mockk<QueueService>()
    private val storeAccess = mockk<StoreAccessService>(relaxed = true)
    private val service = OfflineReconciliationService(queueService, storeAccess)
    private val storeId = UUID.randomUUID()
    private val principal = mockk<AdminPrincipal>(relaxed = true)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()

    @Test
    fun `reconcile applies each transition and propagates per-item results`() = runTest {
        coEvery { queueService.reconcileTerminalTransition(storeId, t1, OfflineAction.SERVE) } returns "applied"
        coEvery { queueService.reconcileTerminalTransition(storeId, t2, OfflineAction.CANCEL) } returns "gone"

        val res = service.reconcile(
            storeId,
            ReconcileOfflineRequest(
                listOf(
                    OfflineTransition(t1, OfflineAction.SERVE, null),
                    OfflineTransition(t2, OfflineAction.CANCEL, null)
                )
            ),
            principal
        )

        assertThat(res.results.map { it.result }).containsExactly("applied", "gone")
        coVerify(exactly = 1) { storeAccess.requireStoreAccess(principal, storeId) }
    }

    @Test
    fun `reconcile with no_show transition maps to gone`() = runTest {
        val t3 = UUID.randomUUID()
        coEvery { queueService.reconcileTerminalTransition(storeId, t3, OfflineAction.NO_SHOW) } returns "superseded"

        val res = service.reconcile(
            storeId,
            ReconcileOfflineRequest(listOf(OfflineTransition(t3, OfflineAction.NO_SHOW, null))),
            principal
        )

        assertThat(res.results).hasSize(1)
        assertThat(res.results[0].result).isEqualTo("superseded")
        assertThat(res.results[0].ticketId).isEqualTo(t3)
    }

    @Test
    fun `reconcile empty transitions list returns empty results`() = runTest {
        val res = service.reconcile(
            storeId,
            ReconcileOfflineRequest(emptyList()),
            principal
        )

        assertThat(res.results).isEmpty()
        coVerify(exactly = 1) { storeAccess.requireStoreAccess(principal, storeId) }
    }
}
