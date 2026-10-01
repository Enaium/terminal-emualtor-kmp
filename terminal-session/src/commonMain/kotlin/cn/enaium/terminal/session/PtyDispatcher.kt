package cn.enaium.terminal.session

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher the pty reader runs on.
 *
 * The reader performs a *blocking* read, so it must not run on a dispatcher
 * shared with CPU work: the JVM/Android implementations use
 * `Dispatchers.IO`, the native ones use a dedicated single-thread context
 * because native coroutines have no public IO dispatcher.
 */
internal expect val ptyReaderDispatcher: CoroutineDispatcher
