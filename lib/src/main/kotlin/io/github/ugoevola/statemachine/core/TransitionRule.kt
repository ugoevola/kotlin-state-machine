package io.github.ugoevola.statemachine.core

import io.github.ugoevola.statemachine.contract.MachineEvent
import kotlin.reflect.KClass

data class TransitionRule<S, E : MachineEvent<R>, C, R>(
    val from: S,
    val on: KClass<out E>,
    val guard: (C) -> Boolean = { true },
    val to: S,
    val action: ((C) -> R?)? = null
)