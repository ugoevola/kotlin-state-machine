package io.github.ugoevola.statemachine.dsl

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import io.github.ugoevola.statemachine.core.StateMachineDefinition

fun <S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>> stateMachine(
    block: StateMachineBuilder<S, E, C, T>.() -> Unit
): StateMachineDefinition<S, E, C, T> =
    StateMachineBuilder<S, E, C, T>().apply(block).build()