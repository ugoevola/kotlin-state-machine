package io.github.ugoevola.statemachine.contract

interface MachineContext<T : MachineTransaction<*>> {
    val transaction: T?
}