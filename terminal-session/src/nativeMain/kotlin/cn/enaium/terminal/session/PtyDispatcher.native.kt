@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cn.enaium.terminal.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Native coroutines expose no public IO dispatcher, so the blocking pty read
 * gets its own single-thread view of the default pool instead of occupying a
 * shared worker.
 */
internal actual val ptyReaderDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1)
