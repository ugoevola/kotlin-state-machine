package io.github.ugoevola.statemachine.core

import io.github.ugoevola.statemachine.contract.MachineContext
import io.github.ugoevola.statemachine.contract.MachineEvent
import io.github.ugoevola.statemachine.contract.MachineTransaction
import kotlin.collections.filter

class StateMachineDefinition<S, E : MachineEvent<*>, C : MachineContext<T>, T : MachineTransaction<S>>(
    private val rules: List<TransitionRule<S, E, C, *>>,
    private val onEnterActions: Map<S, (C) -> Any> = emptyMap(),
    private val transientStates: Map<S, (C) -> S> = emptyMap()
) {
    fun <R> transition(current: S, event: MachineEvent<R>, context: C): R? {
        val matchingRules = rules.filter { it.on == event::class }
        val results = mutableListOf<Any?>()
        var newState = current
        var noValidTransition = true

        for (rule in matchingRules) {
            if (rule.from != newState) continue
            if (rule.guard(context)) {
                noValidTransition = false
                val actionResult = rule.action?.invoke(context) ?: Unit
                if (actionResult != Unit) results.add(actionResult)
                newState = enterState(rule.to, context, results)
            }
        }

        if (noValidTransition) throw IllegalStateException(
            "No valid transition from [$newState] on [${event::class.simpleName}]"
        )

        return event.collect(results)
    }

    private fun enterState(state: S, context: C, results: MutableList<Any?>): S {
        context.transaction!!.currentState = state
        val result = onEnterActions[state]?.invoke(context) ?: Unit
        if (result != Unit) results.add(result)

        return transientStates[state]
            ?.let { next -> enterState(next(context), context, results) }
            ?: state
    }
}