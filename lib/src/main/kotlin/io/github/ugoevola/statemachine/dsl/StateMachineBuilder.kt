package io.github.ugoevola.statemachine.dsl

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import io.github.ugoevola.statemachine.core.StateMachineDefinition
import io.github.ugoevola.statemachine.core.TransitionRule
import kotlin.reflect.KClass

class StateMachineBuilder<S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>> {

    internal val rules = mutableListOf<TransitionRule<S, E, C, *>>()
    internal val onEnterActions = mutableMapOf<S, (C) -> Any>()
    internal val transientStates = mutableMapOf<S, (C) -> S>()         // feature 2

    fun <R, EV : MachineEvent<R>> transition(
        from: S, on: KClass<out EV>, guard: (C) -> Boolean = { true }, to: S, action: ((C) -> R)? = null
    ) {
        @Suppress("UNCHECKED_CAST")
        rules.add(TransitionRule(from, on, guard, to, action) as TransitionRule<S, E, C, *>)
    }

    fun onEnter(state: S, action: (C) -> Any) {
        onEnterActions[state] = action
    }

    fun include(group: TransitionGroup<S, E, C, T>) {
        rules.addAll(group.rules)
        onEnterActions.putAll(group.onEnterActions)
        transientStates.putAll(group.transientStates)
    }

    fun transientState(state: S, then: S, action: ((C) -> Any)? = null) {
        transientState(state = state, then = { then }, action = action)
    }

    fun transientState(state: S, then: (C) -> S, action: ((C) -> Any)? = null) {
        transientStates[state] = then
        if (action != null) onEnterActions[state] = action
    }

    fun build() = StateMachineDefinition(rules.toList(), onEnterActions.toMap(), transientStates.toMap())
}