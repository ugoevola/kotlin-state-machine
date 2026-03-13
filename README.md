# kotlin-state-machine

A lightweight, type-safe state machine library for Kotlin.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ugoevola/kotlin-state-machine)](https://central.sonatype.com/artifact/io.github.ugoevola/kotlin-state-machine)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

---

## Installation

```kotlin
dependencies {
    implementation("io.github.ugoevola:kotlin-state-machine:1.0.0")
}
```

---

## Core concepts

The library is built around four contracts and one engine.

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
enum class OrderState { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

sealed interface OrderEvent<out R> : MachineEvent<R> {
    data object Confirm : OrderEvent<String>
    data object Ship    : OrderEvent<String>
    data object Deliver : OrderEvent<Unit>
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
        from   = OrderState.CONFIRMED,
        on     = OrderEvent.Ship::class,
        guard  = { ctx -> ctx.isPriority },   // only allowed for priority orders
        to     = OrderState.SHIPPED,
        action = { "order-shipped" }
    )

    transition(
        from = OrderState.SHIPPED,
        on   = OrderEvent.Deliver::class,
        to   = OrderState.DELIVERED
        // no action = returns null
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
// result  -> "order-confirmed"
// tx.currentState -> CONFIRMED

machine.applyEvent(OrderEvent.Ship, ctx)
// tx.currentState -> SHIPPED
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

`onEnter` registers a side-effect that fires whenever the machine **enters** a given state, regardless of which transition caused it.

```kotlin
onEnter(OrderState.CANCELLED) { ctx ->
    notificationService.notifyCustomer(ctx.transaction!!.orderId)
}
```

Execution order for a transition: **`action`** → **`onEnter`**.

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

`MachineTransactionPort` is an optional persistence hook. It is called **after** every successful transition with the updated transaction object.

```kotlin
class OrderTransactionAdapter(
    private val repository: OrderRepository
) : MachineTransactionPort<OrderTransaction> {
    override fun update(transaction: OrderTransaction) {
        repository.save(transaction)
    }
}

val machine = StateMachine(
    stateMachineDefinition   = orderMachineDefinition,
    machineTransactionPort   = OrderTransactionAdapter(repository)
)
```

The port is **not called** if the transition throws (no valid rule, guard failure, null transaction).

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
enum class OrderState { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

sealed interface OrderEvent<out R> : MachineEvent<R> {
    data object Confirm : OrderEvent<String>
    data object Ship    : OrderEvent<String>
    data object Deliver : OrderEvent<Unit>
    data object Cancel  : OrderEvent<String>
}

data class OrderTransaction(override var currentState: OrderState) : MachineTransaction<OrderState>
data class OrderContext(override val transaction: OrderTransaction?, val isPriority: Boolean = false) : MachineContext<OrderTransaction>

val definition = stateMachine<OrderState, OrderEvent<*>, OrderContext, OrderTransaction> {
    transition(from = PENDING,   on = Confirm::class, to = CONFIRMED, action = { "confirmed" })
    transition(from = CONFIRMED, on = Ship::class,    to = SHIPPED,   guard = { it.isPriority }, action = { "shipped" })
    transition(from = SHIPPED,   on = Deliver::class, to = DELIVERED)
    transition(from = PENDING,   on = Cancel::class,  to = CANCELLED, action = { "cancelled" })
    transition(from = CONFIRMED, on = Cancel::class,  to = CANCELLED, action = { "cancelled" })

    onEnter(DELIVERED)  { println("Order delivered!") }
    onEnter(CANCELLED)  { println("Order cancelled.") }
}

fun main() {
    val tx  = OrderTransaction(PENDING)
    val ctx = OrderContext(tx, isPriority = true)
    val machine = StateMachine(definition)

    machine.applyEvent(Confirm, ctx)  // PENDING → CONFIRMED
    machine.applyEvent(Ship, ctx)     // CONFIRMED → SHIPPED
    machine.applyEvent(Deliver, ctx)  // SHIPPED → DELIVERED  →  prints "Order delivered!"
}
```

---

## License

MIT © [Ugo Evola](https://github.com/ugoevola)