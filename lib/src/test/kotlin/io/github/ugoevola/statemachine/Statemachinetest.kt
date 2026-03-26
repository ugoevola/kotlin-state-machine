package io.github.ugoevola.statemachine

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import io.github.ugoevola.statemachine.contract.MachineTransactionPort
import io.github.ugoevola.statemachine.core.StateMachine
import io.github.ugoevola.statemachine.core.StateMachineDefinition
import io.github.ugoevola.statemachine.dsl.stateMachine
import io.github.ugoevola.statemachine.dsl.transitionGroup
import kotlin.test.*

// ─── Chained-transient fixtures (top-level required by Kotlin) ───────────────

enum class ChainState { A, B, C, D }

sealed interface ChainEvent<out R> : MachineEvent<R> {
    data object Go : ChainEvent<String>
}

data class ChainTransaction(override var currentState: ChainState) : MachineTransaction<ChainState>
data class ChainContext(override val transaction: ChainTransaction?) : MachineContext<ChainTransaction>

// ─── Test fixtures ────────────────────────────────────────────────────────────

enum class OrderState {
    PENDING, CONFIRMED, SHIPPED, DELIVERING, DELIVERED, CANCELLED
    //                           ^^^^^^^^^^
    // DELIVERING is a transient state: fires its onEnter action then automatically
    // advances to DELIVERED.
}

sealed interface OrderEvent<out R> : MachineEvent<R> {
    data object Confirm  : OrderEvent<String>
    data object Ship     : OrderEvent<String>
    data object Deliver  : OrderEvent<String>
    data object Cancel   : OrderEvent<String>
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

// ─── Shared transition groups ─────────────────────────────────────────────────

/** Groups are defined once and reused across multiple machine definitions in tests. */
private val confirmGroup =
    transitionGroup<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
        transition(
            from   = OrderState.PENDING,
            on     = OrderEvent.Confirm::class,
            to     = OrderState.CONFIRMED,
            action = { ctx ->
                ctx.transaction!!.log.add("confirm-action")
                "order-confirmed"
            }
        )
        onEnter(OrderState.CONFIRMED) { ctx ->
            ctx.transaction!!.log.add("entered-confirmed")
        }
    }

private val cancelGroup =
    transitionGroup<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
        transition(
            from   = OrderState.PENDING,
            on     = OrderEvent.Cancel::class,
            to     = OrderState.CANCELLED,
            action = { "order-cancelled-from-pending" }
        )
        transition(
            from   = OrderState.CONFIRMED,
            on     = OrderEvent.Cancel::class,
            to     = OrderState.CANCELLED,
            action = { "order-cancelled-from-confirmed" }
        )
        onEnter(OrderState.CANCELLED) { ctx ->
            ctx.transaction!!.log.add("entered-cancelled")
        }
    }

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Builds a complete definition using [include] for the shared groups
 * and [transientState] for DELIVERING → DELIVERED.
 */
private fun buildDefinition() =
    stateMachine {

        include(confirmGroup)
        include(cancelGroup)

        transition(
            from  = OrderState.CONFIRMED,
            on    = OrderEvent.Ship::class,
            guard = { ctx -> ctx.isPriority },
            to    = OrderState.SHIPPED,
            action = { ctx ->
                ctx.transaction!!.log.add("ship-action")
                "order-shipped"
            }
        )

        // DELIVERING is transient: onEnter runs, then the machine automatically
        // advances to DELIVERED without requiring a new event.
        transientState(
            state  = OrderState.DELIVERING,
            then   = OrderState.DELIVERED,
            action = { ctx ->
                ctx.transaction!!.log.add("delivering-action")
                "order-delivering"
            }
        )

        transition(
            from = OrderState.SHIPPED,
            on   = OrderEvent.Deliver::class,
            to   = OrderState.DELIVERING
        )
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
    fun `currentState is updated before onEnter is called`() {
        var stateInsideOnEnter: OrderState? = null
        val definition =
            stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
                transition(from = OrderState.PENDING, on = OrderEvent.Confirm::class, to = OrderState.CONFIRMED)
                onEnter(OrderState.CONFIRMED) { ctx ->
                    stateInsideOnEnter = ctx.transaction!!.currentState
                }
            }
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        buildMachine(definition).applyEvent(OrderEvent.Confirm, ctx)

        assertEquals(OrderState.CONFIRMED, stateInsideOnEnter)
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
        val ctx = OrderContext(tx, isPriority = false)
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
        val tx  = OrderTransaction(OrderState.DELIVERED)
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
        val tx  = OrderTransaction(OrderState.PENDING)
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
            override fun save(transaction: OrderTransaction): OrderTransaction {
                updatedTx = transaction
                return updatedTx
            }
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
            override fun save(transaction: OrderTransaction): OrderTransaction {
                portCalled = true
                return transaction
            }
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

    // ── transitionGroup / include ─────────────────────────────────────────────

    @Test
    fun `include imports transitions from a group`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        // Machine built with only the confirmGroup included
        val definition = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
            include(confirmGroup)
        }
        val machine = buildMachine(definition)

        machine.applyEvent(OrderEvent.Confirm, ctx)

        assertEquals(OrderState.CONFIRMED, tx.currentState)
    }

    @Test
    fun `include imports onEnter actions from a group`() {
        val tx  = OrderTransaction(OrderState.PENDING)
        val ctx = OrderContext(tx)
        val definition = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
            include(confirmGroup)
        }
        buildMachine(definition).applyEvent(OrderEvent.Confirm, ctx)

        assertContains(tx.log, "entered-confirmed")
    }

    @Test
    fun `multiple groups can be included in the same machine`() {
        val tx  = OrderTransaction(OrderState.CONFIRMED)
        val ctx = OrderContext(tx)
        val definition = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
            include(confirmGroup)
            include(cancelGroup)
        }
        val machine = buildMachine(definition)

        val result = machine.applyEvent(OrderEvent.Cancel, ctx)

        assertEquals("order-cancelled-from-confirmed", result)
        assertEquals(OrderState.CANCELLED, tx.currentState)
    }

    @Test
    fun `group can be reused across different machine definitions`() {
        val definitionA = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
            include(cancelGroup)
        }
        val definitionB = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
            include(cancelGroup)
        }

        val txA = OrderTransaction(OrderState.PENDING)
        val txB = OrderTransaction(OrderState.PENDING)

        buildMachine(definitionA).applyEvent(OrderEvent.Cancel, OrderContext(txA))
        buildMachine(definitionB).applyEvent(OrderEvent.Cancel, OrderContext(txB))

        assertEquals(OrderState.CANCELLED, txA.currentState)
        assertEquals(OrderState.CANCELLED, txB.currentState)
    }

    // ── transientState ────────────────────────────────────────────────────────

    @Test
    fun `transient state executes its action then advances automatically`() {
        val tx  = OrderTransaction(OrderState.SHIPPED)
        val ctx = OrderContext(tx, isPriority = true)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Deliver, ctx)

        // The machine passed through DELIVERING and landed on DELIVERED automatically
        assertEquals(OrderState.DELIVERED, tx.currentState)
        assertContains(tx.log, "delivering-action")
    }

    @Test
    fun `transient state is never the final resting state`() {
        val tx  = OrderTransaction(OrderState.SHIPPED)
        val ctx = OrderContext(tx, isPriority = true)
        val machine = buildMachine(buildDefinition())

        machine.applyEvent(OrderEvent.Deliver, ctx)

        assertNotEquals(OrderState.DELIVERING, tx.currentState)
    }

    @Test
    fun `transient state result is forwarded to applyEvent caller`() {
        val tx  = OrderTransaction(OrderState.SHIPPED)
        val ctx = OrderContext(tx, isPriority = true)
        val machine = buildMachine(buildDefinition())

        val result = machine.applyEvent(OrderEvent.Deliver, ctx)

        // The action on DELIVERING returns "order-delivering"
        assertEquals("order-delivering", result)
    }

    @Test
    fun `chained transient states are traversed in a single event`() {
        // ChainState.A → B (transient) → C (transient) → D
        val log = mutableListOf<String>()
        val definition = stateMachine<ChainState, ChainEvent<*>, ChainContext, ChainTransaction> {
            transition(from = ChainState.A, on = ChainEvent.Go::class, to = ChainState.B)
            transientState(ChainState.B, then = ChainState.C) { log.add("B"); "from-B" }
            transientState(ChainState.C, then = ChainState.D) { log.add("C"); "from-C" }
        }

        val tx  = ChainTransaction(ChainState.A)
        val ctx = ChainContext(tx)
        StateMachine(definition).applyEvent(ChainEvent.Go, ctx)

        assertEquals(ChainState.D, tx.currentState)
        assertEquals(listOf("B", "C"), log)
    }

    @Test
    fun `port is called with the final state after transient traversal`() {
        val tx   = OrderTransaction(OrderState.SHIPPED)
        val ctx  = OrderContext(tx, isPriority = true)
        var updatedTx: OrderTransaction? = null
        val port = object : MachineTransactionPort<OrderTransaction> {
            override fun save(transaction: OrderTransaction): OrderTransaction {
                updatedTx = transaction
                return updatedTx
            }
        }
        val machine = buildMachine(buildDefinition(), port)

        machine.applyEvent(OrderEvent.Deliver, ctx)

        assertNotNull(updatedTx)
        assertEquals(OrderState.DELIVERED, updatedTx.currentState)
    }
}