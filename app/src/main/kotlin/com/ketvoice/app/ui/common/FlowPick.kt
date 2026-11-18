package com.ketvoice.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One part of a StateFlow with its value now, so a composable follows only that part without reading `.value`. */
internal class FlowPick<R>(val flow: Flow<R>, val initial: R)

internal fun <T, R> StateFlow<T>.pick(part: (T) -> R) = FlowPick(map(part).distinctUntilChanged(), part(value))

@Composable
internal fun <R> FlowPick<R>.collectPick(): State<R> = flow.collectAsState(initial)
