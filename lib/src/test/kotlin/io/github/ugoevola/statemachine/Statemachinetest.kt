package io.github.ugoevola.statemachine

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import io.github.ugoevola.statemachine.contract.MachineTransactionPort
import io.github.ugoevola.statemachine.core.StateMachine
import io.github.ugoevola.statemachine.core.StateMachineDefinition
import io.github.ugoevola.statemachine.dsl.stateMachine
import kotlin.test.*

// ─── Test fixtures ────────────────────────────────────────────────────────────

enum class OrderState { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

sealed interface OrderEvent<out R> : MachineEvent<R> {
    data object Confirm  : OrderEvent<String>
    data object Ship     : OrderEvent<String>
    data object Deliver  : OrderEvent<String>
    data object Cancel   : OrderEvent<String>
    // Event with no action result
    data object Reset    : OrderEvent<Unit>
}

data class OrderTransaction(
    override var currentState: OrderState,
    var log: MutableList<String> = mutableListOf()
) : MachineTransaction<OrderState>

data class OrderContext(
    override val transaction: OrderTransaction?,
    val isPriority: Boolean = false
) : MachineContext<OrderTransaction>

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun buildDefinition(
    isPriority: Boolean = false
) = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {

    transition(
        from  = OrderState.PENDING,
        on    = OrderEvent.Confirm::class,
        to    = OrderState.CONFIRMED,
        action = { ctx ->
            ctx.transaction!!.log.add("confirm-action")
            "order-confirmed"
        }
    )

    transition(
        from  = OrderState.CONFIRMED,
        on    = OrderEvent.Ship::class,
        guard = { ctx -> ctx.isPriority },          // only if priority
        to    = OrderState.SHIPPED,
        action = { ctx ->
            ctx.transaction!!.log.add("ship-action")
            "order-shipped"
        }
    )

    transition(
        from  = OrderState.SHIPPED,
        on    = OrderEvent.Deliver::class,
        to    = OrderState.DELIVERED
        // no action → result will be null
    )

    transition(
        from  = OrderState.PENDING,
        on    = OrderEvent.Cancel::class,
        to    = OrderState.CANCELLED,
        action = { "order-cancelled-from-pending" }
    )

    transition(
        from  = OrderState.CONFIRMED,
        on    = OrderEvent.Cancel::class,
        to    = OrderState.CANCELLED,
        action = { "order-cancelled-from-confirmed" }
    )

    onEnter(OrderState.CONFIRMED) { ctx ->
        ctx.transaction!!.log.add("entered-confirmed")
    }

    onEnter(OrderState.CANCELLED) { ctx ->
        ctx.transaction!!.log.add("entered-cancelled")
    }
}

private fun buildMachine(
    definition: StateMachineDefinition<OrderState, OrderEvent<*>, OrderContext, OrderTransaction>,
    port: MachineTransactionPort<OrderTransaction>? = null
) = StateMachine(definition, port)

// ─── Tests ────────────────────────────────────────────────────────────────────

class StateMachineTest {

    // ── Basic transition ──────────────────────────────────────────────────────

    @Test
    fun `basic transition updates currentState`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Confirm, ctx)

        assertEquals(OrderState.CONFIRMED, tx.currentState)
    }

    @Test
    fun `basic transition returns action result`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        val result = machine.applyEvent(OrderEvent.Confirm, ctx)

        assertEquals("order-confirmed", result)
    }

    // ── Action & onEnter ──────────────────────────────────────────────────────

    @Test
    fun `action is invoked during transition`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Confirm, ctx)

        assertContains(tx.log, "confirm-action")
    }

    @Test
    fun `onEnter action is invoked when entering a state`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Confirm, ctx)

        assertContains(tx.log, "entered-confirmed")
    }

    @Test
    fun `both transition action and onEnter action are invoked in order`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Confirm, ctx)

        assertEquals(listOf("confirm-action", "entered-confirmed"), tx.log)
    }

    @Test
    fun `transition with no action returns null`() {
        val tx  = OrderTransaction(OrderState.SHIPPED)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        val result = machine.applyEvent(OrderEvent.Deliver, ctx)

        assertNull(result)
        assertEquals(OrderState.DELIVERED, tx.currentState)
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    fun `guard allows transition when condition is true`() {
        val tx  = OrderTransaction(OrderState.CONFIRMED)
        val ctx = OrderContext(tx, isPriority = true)
        val machine = buildMachine(buildDefinition())

        val result = machine.applyEvent(OrderEvent.Ship, ctx)

        assertEquals("order-shipped", result)
        assertEquals(OrderState.SHIPPED, tx.currentState)
    }

    @Test
    fun `guard blocks transition and throws when condition is false`() {
        val tx  = OrderTransaction(OrderState.CONFIRMED)
        val ctx = OrderContext(tx, isPriority = false)   // guard will fail
        val machine = buildMachine(buildDefinition())

        assertFailsWith<IllegalStateException> {
            machine.applyEvent(OrderEvent.Ship, ctx)
        }
    }

    // ── Same event, different origin states ──────────────────────────────────

    @Test
    fun `cancel from PENDING returns correct result`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        val result = machine.applyEvent(OrderEvent.Cancel, ctx)

        assertEquals("order-cancelled-from-pending", result)
        assertEquals(OrderState.CANCELLED, tx.currentState)
    }

    @Test
    fun `cancel from CONFIRMED returns correct result`() {
        val tx  = OrderTransaction(OrderState.CONFIRMED)
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        val result = machine.applyEvent(OrderEvent.Cancel, ctx)

        assertEquals("order-cancelled-from-confirmed", result)
        assertEquals(OrderState.CANCELLED, tx.currentState)
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    fun `throws when no rule matches the current state`() {
        val tx  = OrderTransaction(OrderState.DELIVERED)   // terminal state
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        val ex = assertFailsWith<IllegalStateException> {
            machine.applyEvent(OrderEvent.Confirm, ctx)
        }
        assertTrue(ex.message!!.contains("DELIVERED"))
        assertTrue(ex.message!!.contains("Confirm"))
    }

    @Test
    fun `throws when event is fired from wrong state`() {
        val tx  = OrderTransaction(OrderState.PENDING)   // not yet confirmed
        val ctx = OrderContext(tx)
        val machine = buildMachine(buildDefinition())

        assertFailsWith<IllegalStateException> {
            machine.applyEvent(OrderEvent.Ship, ctx)
        }
    }

    @Test
    fun `throws when transaction is null`() {
        val ctx = OrderContext(transaction = null)
        val machine = buildMachine(buildDefinition())

        assertFailsWith<IllegalArgumentException> {
            machine.applyEvent(OrderEvent.Confirm, ctx)
        }
    }

    // ── MachineTransactionPort ────────────────────────────────────────────────

    @Test
    fun `port update is called after successful transition`() {
        val tx   = OrderTransaction(OrderState.PENDING)
        val ctx  = OrderContext(tx)
        var updatedTx: OrderTransaction? = null
        val port = object : MachineTransactionPort<OrderTransaction> {
            override fun update(transaction: OrderTransaction) { updatedTx = transaction }
        }
        val machine = buildMachine(buildDefinition(), port)

        machine.applyEvent(OrderEvent.Confirm, ctx)

        assertNotNull(updatedTx)
        assertEquals(OrderState.CONFIRMED, updatedTx.currentState)
    }

    @Test
    fun `port update is NOT called when transition throws`() {
        val tx   = OrderTransaction(OrderState.DELIVERED)
        val ctx  = OrderContext(tx)
        var portCalled = false
        val port = object : MachineTransactionPort<OrderTransaction> {
            override fun update(transaction: OrderTransaction) { portCalled = true }
        }
        val machine = buildMachine(buildDefinition(), port)

        assertFailsWith<IllegalStateException> {
            machine.applyEvent(OrderEvent.Confirm, ctx)
        }
        assertFalse(portCalled)
    }

    // ── Sequential transitions ────────────────────────────────────────────────

    @Test
    fun `full happy path transitions correctly`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx, isPriority = true)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Confirm,  ctx)
        machine.applyEvent(OrderEvent.Ship,     ctx)
        machine.applyEvent(OrderEvent.Deliver,  ctx)

        assertEquals(OrderState.DELIVERED, tx.currentState)
    }
}