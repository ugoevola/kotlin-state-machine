package io.github.ugoevola.statemachine.contract

interface MachineTransactionPort<T : MachineTransaction<*>> {
    fun update(transaction: T)
}