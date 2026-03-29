# kotlin-state-machine

A lightweight, type-safe state machine library for Kotlin.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ugoevola/kotlin-state-machine)](https://central.sonatype.com/artifact/io.github.ugoevola/kotlin-state-machine)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

---

## Installation

```kotlin
dependencies {
    implementation("io.github.ugoevola:kotlin-state-machine:1.1.2")
}
```

---

## Core concepts

| Concept | Role |
|---|---|
| `MachineEvent<R>` | An event that triggers a transition and carries a return type `R` |
| `MachineContext<T>` | Holds the current transaction and any additional data needed by guards/actions |
| `MachineTransaction<S>` | Mutable holder for the current state `S` |
| `MachineTransactionPort<T>` | Optional side-effect hook called after every successful transition (e.g. DB persistence) |
| `StateMachine` | The engine — accepts events and drives the machine forward |

---

## Quick start

### 1. Define your states and events

```kotlin
enum class OrderState { PENDING, CONFIRMED, SHIPPED, DELIVERING, DELIVERED, CANCELLED }
//                                                   ^^^^^^^^^^
// DELIVERING is a transient state — see the "Transient states" section below.

sealed interface OrderEvent<out R> : MachineEvent<R> {
    data object Confirm : OrderEvent<String>
    data object Ship    : OrderEvent<String>
    data object Deliver : OrderEvent<String>
    data object Cancel  : OrderEvent<String>
}
```

### 2. Implement the transaction and context

```kotlin
data class OrderTransaction(
    override var currentState: OrderState
) : MachineTransaction<OrderState>

data class OrderContext(
    override val transaction: OrderTransaction?,
    val isPriority: Boolean = false
) : MachineContext<OrderTransaction>
```

### 3. Define your state machine with the DSL

```kotlin
val orderMachineDefinition = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {

    transition(
        from   = OrderState.PENDING,
        on     = OrderEvent.Confirm::class,
        to     = OrderState.CONFIRMED,
        action = { "order-confirmed" }
    )

    transition(
        from  = OrderState.CONFIRMED,
        on    = OrderEvent.Ship::class,
        guard = { ctx -> ctx.isPriority },
        to    = OrderState.SHIPPED,
        action = { "order-shipped" }
    )

    // After entering DELIVERING (onEnter fires), the machine automatically
    // advances to DELIVERED — no extra event needed.
    transientState(
        state  = OrderState.DELIVERING,
        then   = OrderState.DELIVERED,
        action = { ctx -> notifyCarrier(ctx) }
    )

    transition(
        from = OrderState.SHIPPED,
        on   = OrderEvent.Deliver::class,
        to   = OrderState.DELIVERING
    )

    onEnter(OrderState.CONFIRMED) { ctx ->
        println("Entered CONFIRMED for order ${ctx.transaction?.currentState}")
    }
}
```

### 4. Instantiate and use the machine

```kotlin
val machine = StateMachine(orderMachineDefinition)

val tx  = OrderTransaction(currentState = OrderState.PENDING)
val ctx = OrderContext(transaction = tx, isPriority = true)

val result: String? = machine.applyEvent(OrderEvent.Confirm, ctx)
// result          -> "order-confirmed"
// tx.currentState -> CONFIRMED

machine.applyEvent(OrderEvent.Ship, ctx)
// tx.currentState -> SHIPPED

machine.applyEvent(OrderEvent.Deliver, ctx)
// Passes through DELIVERING (action fires), lands on DELIVERED automatically.
// tx.currentState -> DELIVERED
```

---

## Transitions

A transition rule has five parameters:

```kotlin
transition(
    from   = MyState.A,          // origin state
    on     = MyEvent.Foo::class, // triggering event class
    guard  = { ctx -> ... },     // optional — defaults to { true }
    to     = MyState.B,          // target state
    action = { ctx -> ... }      // optional — return value forwarded to the caller
)
```

The `action` lambda return type must match the return type `R` declared on the event.  
If no `action` is provided, `applyEvent` returns `null`.

---

## Guards

A guard is a predicate `(C) -> Boolean` evaluated at transition time. If it returns `false`, the transition is skipped. If **no valid transition** is found (no matching rule or all guards failed), an `IllegalStateException` is thrown.

```kotlin
transition(
    from  = OrderState.CONFIRMED,
    on    = OrderEvent.Ship::class,
    guard = { ctx -> ctx.isPriority },  // blocks non-priority orders
    to    = OrderState.SHIPPED
)
```

---

## onEnter actions

`onEnter` registers a side-effect that fires whenever the machine **enters** a given state, regardless of which transition caused it. `currentState` is updated **before** `onEnter` is called, so the transaction already reflects the new state inside the callback.

```kotlin
onEnter(OrderState.CANCELLED) { ctx ->
    notificationService.notifyCustomer(ctx.transaction!!.orderId)
    // ctx.transaction.currentState == CANCELLED here
}
```

Execution order for a transition: **`action`** → **`onEnter`**.

---

## Transient states

A transient state is a state that runs its side-effect (via `onEnter`) and then **automatically advances** to the next state without waiting for an external event. This is useful for intermediate processing steps that have a clear, unconditional successor.

```kotlin
transientState(
    state  = MyState.PROCESSING,  // the transient state
    then   = MyState.PROCESSED,   // the state to advance to automatically
    action = { ctx -> doWork(ctx) }  // optional — equivalent to onEnter
)
```

The `action` parameter is a shorthand for `onEnter(state) { ... }`. You can omit it and register the side-effect with a standalone `onEnter` call instead.

Transient states can be **chained**: if the successor state is itself transient, the machine keeps advancing until it reaches a stable state — all within a single `applyEvent` call.

```kotlin
// A → B (transient) → C (transient) → D
transientState(state = MyState.B, then = MyState.C) { ctx -> stepB(ctx) }
transientState(state = MyState.C, then = MyState.D) { ctx -> stepC(ctx) }

transition(from = MyState.A, on = MyEvent.Go::class, to = MyState.B)
// applyEvent(Go, ctx)  →  A → B → C → D  in one call
```

**Return value:** the first non-null result produced across the entire chain (transition action + all transient state actions) is returned to the caller, following the standard `collect` behaviour.

**Port:** `MachineTransactionPort.update` is called once, **after** the full chain has settled, with the final stable state.

---

## Splitting the definition into groups

For large machines, use `transitionGroup` to extract cohesive sets of transitions into separate files, then `include` them in the main definition.

```kotlin
// LoginTransitions.kt
val loginTransitions =
    transitionGroup<AuthorizationState, AuthorizationEvent<*>, AuthorizationContext, AuthorizationTransactionDto> {

        transition(
            from = LOGIN_REQUIRED,
            on   = LoginRequest::class,
            to   = AUTHENTICATION_IN_PROGRESS
        )
        transition(
            from  = AUTHENTICATION_IN_PROGRESS,
            on    = LoginRequest::class,
            guard = { !it.hasConsented },
            to    = CONSENT_REQUIRED
        )
        transition(
            from  = AUTHENTICATION_IN_PROGRESS,
            on    = LoginRequest::class,
            guard = { it.hasConsented },
            to    = CODE_GENERATION_IN_PROGRESS
        )

        onEnter(LOGIN_REQUIRED)             { ctx -> redirectUtils.uriToLoginPage(ctx.transaction!!) }
        onEnter(AUTHENTICATION_IN_PROGRESS) { ctx -> login(ctx) }
    }

// AuthorizationStateMachine.kt
@Bean
fun machineDefinition(...) =
    stateMachine<AuthorizationState, AuthorizationEvent<*>, AuthorizationContext, AuthorizationTransactionDto> {
        include(loginTransitions)
        include(consentTransitions)
        include(codeTransitions)
        // any remaining transitions...
    }
```

`include` merges all transition rules, `onEnter` actions, and transient state declarations from the group into the current builder. Groups are plain values — they can be defined at the top level of a file, passed as constructor parameters, or reused across multiple machine definitions.

**Recommended file layout for a consumer project:**

```
statemachine/
├── AuthorizationStateMachine.kt   ← main definition (include calls only)
├── AuthorizationContext.kt
├── AuthorizationEvent.kt
├── AuthorizationState.kt
└── groups/
    ├── LoginTransitions.kt
    ├── ConsentTransitions.kt
    ├── CodeTransitions.kt
    └── TokenTransitions.kt
```

---

## Return values

Each event declares its return type via its generic parameter `R`:

```kotlin
data object Confirm : OrderEvent<String>   // applyEvent returns String?
data object Deliver : OrderEvent<Unit>     // applyEvent returns Unit? (i.e. null)
```

By default, `MachineEvent.collect` returns the **first non-null result** produced by the transition chain (action + onEnter). You can override `collect` on a specific event to aggregate multiple results differently.

---

## MachineTransactionPort

`MachineTransactionPort` is an optional persistence hook called **after** every successful transition with the updated transaction object.

```kotlin
class OrderTransactionAdapter(
    private val repository: OrderRepository
) : MachineTransactionPort<OrderTransaction> {
    override fun update(transaction: OrderTransaction) {
        repository.save(transaction)
    }
}

val machine = StateMachine(
    stateMachineDefinition = orderMachineDefinition,
    machineTransactionPort = OrderTransactionAdapter(repository)
)
```

The port is **not called** if the transition throws. When transient states are involved it is called **once**, after the full chain has settled, with the final stable state.

---

## Error handling

| Situation | Exception |
|---|---|
| `context.transaction` is `null` | `IllegalArgumentException` |
| No rule matches the current state + event | `IllegalStateException` |
| A rule matches but all guards return `false` | `IllegalStateException` |

```kotlin
try {
    machine.applyEvent(OrderEvent.Ship, ctx)
} catch (e: IllegalStateException) {
    // "No valid transition from [PENDING] on [Ship]"
}
```

---

## Full example — Order lifecycle

```kotlin
enum class OrderState { PENDING, CONFIRMED, SHIPPED, DELIVERING, DELIVERED, CANCELLED }

sealed interface OrderEvent<out R> : MachineEvent<R> {
    data object Confirm : OrderEvent<String>
    data object Ship    : OrderEvent<String>
    data object Deliver : OrderEvent<String>
    data object Cancel  : OrderEvent<String>
}

data class OrderTransaction(override var currentState: OrderState) : MachineTransaction<OrderState>
data class OrderContext(override val transaction: OrderTransaction?, val isPriority: Boolean = false) : MachineContext<OrderTransaction>

val definition = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
    transition(from = PENDING,   on = Confirm::class, to = CONFIRMED,  action = { "confirmed" })
    transition(from = CONFIRMED, on = Ship::class,    to = SHIPPED,    guard = { it.isPriority }, action = { "shipped" })
    transition(from = SHIPPED,   on = Deliver::class, to = DELIVERING)
    transition(from = PENDING,   on = Cancel::class,  to = CANCELLED,  action = { "cancelled" })
    transition(from = CONFIRMED, on = Cancel::class,  to = CANCELLED,  action = { "cancelled" })

    // DELIVERING is transient: fires the action, then advances to DELIVERED automatically.
    transientState(state = DELIVERING, then = DELIVERED) { println("Carrier notified.") }

    onEnter(CANCELLED) { println("Order cancelled.") }
}

fun main() {
    val tx  = OrderTransaction(PENDING)
    val ctx = OrderContext(tx, isPriority = true)
    val machine = StateMachine(definition)

    machine.applyEvent(Confirm, ctx)  // PENDING    → CONFIRMED
    machine.applyEvent(Ship,    ctx)  // CONFIRMED  → SHIPPED
    machine.applyEvent(Deliver, ctx)  // SHIPPED → DELIVERING → DELIVERED  (prints "Carrier notified.")
    // tx.currentState == DELIVERED
}
```

---

## License

MIT © [Ugo Evola](https://github.com/ugoevola)