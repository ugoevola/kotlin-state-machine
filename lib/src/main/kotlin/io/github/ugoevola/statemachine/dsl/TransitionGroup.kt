package io.github.ugoevola.statemachine.dsl

import io.github.ugoevola.statemachine.contract.*
import io.github.ugoevola.statemachine.core.TransitionRule

class TransitionGroup<S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>>(
    internal val rules: List<TransitionRule<S, E, C, *>>,
    internal val onEnterActions: Map<S, (C) -> Any>,
    internal val transientStates: Map<S, (C) -> S>
)

fun <S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>> transitionGroup(
    block: StateMachineBuilder<S, E, C, T>.() -> Unit
): TransitionGroup<S, E, C, T> =
    StateMachineBuilder<S, E, C, T>().apply(block).let {
        TransitionGroup(it.rules.toList(), it.onEnterActions.toMap(), it.transientStates.toMap())
    }