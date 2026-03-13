package io.github.ugoevola.statemachine.contract

interface MachineEvent<out R> {
    @Suppress("UNCHECKED_CAST")
    fun collect(results: List<Any?>): R? = results.firstOrNull() as? R
}