package io.github.ugoevola.statemachine.contract

interface MachineTransactionPort<T : MachineTransaction<*>> {
    fun save(transaction: T): T
}