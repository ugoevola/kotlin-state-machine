package io.github.ugoevola.statemachine.dsl

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import io.github.ugoevola.statemachine.core.StateMachineDefinition
import io.github.ugoevola.statemachine.core.TransitionRule
import kotlin.reflect.KClass

class StateMachineBuilder<S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>> {

    private val rules = mutableListOf<TransitionRule<S, E, C, *>>()
    private val onEnterActions = mutableMapOf<S, (C) -> Any>()

    fun <R, EV : MachineEvent<R>> transition(
        from: S,
        on: KClass<out EV>,
        guard: (C) -> Boolean = { true },
        to: S,
        action: ((C) -> R)? = null
    ) {
        @Suppress("UNCHECKED_CAST")
        rules.add(TransitionRule(from, on, guard, to, action) as TransitionRule<S, E, C, *>)
    }

    fun onEnter(state: S, action: (C) -> Any) {
        onEnterActions[state] = action
    }

    fun build() = StateMachineDefinition(rules.toList(), onEnterActions.toMap())
}