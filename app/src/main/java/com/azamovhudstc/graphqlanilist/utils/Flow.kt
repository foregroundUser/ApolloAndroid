package com.azamovhudstc.graphqlanilist.utils

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.launch

fun <T> Flow<T>.ifChanged() = ifChanged { it }

fun <T, R> Flow<T>.ifChanged(transform: (T) -> R): Flow<T> {
    var observedValueOnce = false
    var lastMappedValue: R? = null

    return filter { value ->
        val mapped = transform(value)
        if (!observedValueOnce || mapped != lastMappedValue) {
            lastMappedValue = mapped
            observedValueOnce = true
            true
        } else {
            false
        }
    }
}

/**
 * Returns a [Flow] containing only changed elements of the lists of the original [Flow].
 *
 * ```
 * Example: Identity function
 * Transform: x -> x (transformed values are the same as original)
 * Original Flow: list(0), list(0, 1), list(0, 1, 2, 3), list(4), list(5, 6, 7, 8)
 * Transformed:
 * (0)          -> (0 emitted because it is a new value)
 *
 * (0, 1)       -> (0 not emitted because same as previous value,
 *                  1 emitted because it is a new value),
 *
 * (0, 1, 2, 3) -> (0 and 1 not emitted because same as previous values,
 *                  2 and 3 emitted because they are new values),
 *
 * (4)          -> (4 emitted because because it is a new value)
 *
 * (5, 6, 7, 8) -> (5, 6, 7, 8 emitted because they are all new values)
 * Returned Flow: 0, 1, 2, 3, 4, 5, 6, 7, 8
 * ---
 *
 * Example: Modulo 2
 * Transform: x -> x % 2 (emit changed values if the result of modulo 2 changed)
 * Original Flow: listOf(1), listOf(1, 2), listOf(3, 4, 5), listOf(3, 4)
 * Transformed:
 * (1)          -> (1 emitted because it is a new value)
 *
 * (1, 0)       -> (1 not emitted because same as previous value with the same transformed value,
 *                  2 emitted because it is a new value),
 *
 * (1, 0, 1)    -> (3, 4, 5 emitted because they are all new values)
 *
 * (1, 0)       -> (3, 4 not emitted because same as previous values with same transformed values)
 *
 * Returned Flow: 1, 2, 3, 4, 5
 * ---
 * ```
 */
fun <T, R> Flow<List<T>>.filterChanged(transform: (T) -> R): Flow<T> {
    var lastMappedValues: Map<T, R>? = null
    return flatMapConcat { values ->
        val lastMapped = lastMappedValues
        val changed = if (lastMapped == null) {
            values
        } else {
            values.filter {
                !lastMapped.containsKey(it) || lastMapped[it] != transform(it)
            }
        }
        lastMappedValues = values.associateWith { transform(it) }
        changed.asFlow()
    }
}

/**
 * Returns a [Flow] containing only values of the original [Flow] where the result array
 * of calling [transform] contains at least one different value.
 *
 * Example:
 * ```
 * Block: x -> [x[0], x[1]]  // Map to first two characters of input
 * Original Flow: "banana", "bandanna", "bus", "apple", "big", "coconut", "circle", "home"
 * Mapped: [b, a], [b, a], [b, u], [a, p], [b, i], [c, o], [c, i], [h, o]
 * Returned Flow: "banana", "bus, "apple", "big", "coconut", "circle", "home"
 * ``
 */
fun <T, R> Flow<T>.ifAnyChanged(transform: (T) -> Array<R>): Flow<T> {
    var observedValueOnce = false
    var lastMappedValues: Array<R>? = null

    return filter { value ->
        val mapped = transform(value)
        val hasChanges = lastMappedValues
            ?.asSequence()
            ?.filterIndexed { i, r -> mapped[i] != r }
            ?.any()

        if (!observedValueOnce || hasChanges == true) {
            lastMappedValues = mapped
            observedValueOnce = true
            true
        } else {
            false
        }
    }
}
//
//@FlowPreview
//fun <T> providerFlow(
//    settings: Settings,
//    block: suspend FlowCollector<T>.(AnimeTypes) -> Unit
//) = SafeFlow(block, settings)

//@FlowPreview
//class SafeFlow<T>(
//    private val block: suspend FlowCollector<T>.(AnimeTypes) -> Unit,
//    private val settings: Settings
//) : AbstractFlow<T>() {
//    override suspend fun collectSafely(collector: FlowCollector<T>) {
//        collector.block(settings.selectedProvider)
//    }
//}

fun <T> LifecycleOwner.collect(
    flow: Flow<T>,
    collector: suspend (T) -> Unit
): Job {
    return lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.catch { e -> logError(e) }.collect(collector)
        }
    }
}

/**
 * Collects data from multiple flows simultaneously and applies the [collector] lambda to the collected data.
 * Supports all types of flows.
 *
 * @param collector The lambda function to apply to the collected data.
 * @param flows The vararg of flows to collect data from.
 */
fun <T> LifecycleOwner.collectAll(
    vararg flows: Flow<T>,
    collector: suspend (T) -> Unit
) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flows.map { flow ->
                launch {
                    flow.collect { data ->
                        collector(data)
                    }
                }
            }
        }
    }
}

fun <T> Fragment.collectLatest(
    flow: Flow<T>,
    collector: suspend (T) -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launchWhenStarted {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.catch { e -> e.printStackTrace() }.collectLatest(collector)
        }
    }
}

fun <T> Flow<List<T>>.reverseIf(predicate: MutableStateFlow<Boolean>): Flow<List<T>> {
    return combine(predicate) { list, isReversed ->
        if (isReversed) {
            list.reversed()
        } else {
            list
        }
    }
}

suspend fun <T, R> Iterable<T>.asyncMap(transform: suspend (T) -> R): List<R> =
    coroutineScope {
        map { element ->
            async { transform(element) }
        }.map { it.await() }
    }

suspend fun <T, R> Iterable<T>.asyncMapIndexed(transform: suspend (index: Int, T) -> R): List<R> =
    coroutineScope {
        mapIndexed { index, element ->
            async { transform(index, element) }
        }.map { it.await() }
    }
