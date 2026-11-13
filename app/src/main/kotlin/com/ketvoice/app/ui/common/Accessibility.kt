package com.ketvoice.app.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** Marks a title as a heading, so screen-reader users can jump between sections. */
fun Modifier.asHeading(): Modifier = semantics { heading() }
