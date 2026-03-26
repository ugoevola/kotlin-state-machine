package io.github.ugoevola.statemachine.core

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import io.github.ugoevola.statemachine.contract.MachineTransactionPort

class StateMachine<S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>>(
    private val stateMachineDefinition: StateMachineDefinition<S, E, C, T>,
    private val machineTransactionPort: MachineTransactionPort<T>? = null
) {

    fun <R> applyEvent(
        event: MachineEvent<R>,
        context: C
    ): R? {
        requireNotNull(context.transaction) { "Transaction is required" }

        val actionsResults = stateMachineDefinition.transition(
            current = context.transaction!!.currentState,
            event = event,
            context = context
        )

        val transaction = context.transaction!!
        machineTransactionPort?.save(transaction)

        return actionsResults
    }
}