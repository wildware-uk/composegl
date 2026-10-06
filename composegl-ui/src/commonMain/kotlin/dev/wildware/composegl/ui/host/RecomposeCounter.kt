package dev.wildware.composegl.ui.host

import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.ObservableComposition

/**
 * Counts every recompose scope a screen runs, from [UiHost.countRecompositions] until [stop].
 *
 * What a test reads either side of a gesture to pin that the gesture composed nothing at all. Every
 * scope that runs counts, including one whose recomposition changes no node: reading a number while
 * composing and then placing nothing differently is still the cost being pinned, and the
 * recomposer's own change count only sees recompositions that changed the tree.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class RecomposeCounter : CompositionObserver {

    /** How many recompose scopes have run since counting began. */
    var scopes = 0
        private set

    internal var handle: CompositionObserverHandle? = null

    fun stop() {
        handle?.dispose()
        handle = null
    }

    override fun onScopeEnter(scope: RecomposeScope) {
        scopes++
    }

    override fun onBeginComposition(composition: ObservableComposition) = Unit
    override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
    override fun onScopeExit(scope: RecomposeScope) = Unit
    override fun onEndComposition(composition: ObservableComposition) = Unit
    override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
    override fun onScopeDisposed(scope: RecomposeScope) = Unit
}
