package io.github.ugoevola.statemachine.contract

interface MachineTransaction<S> {
    var currentState: S
}