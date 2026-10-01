package cn.enaium.terminal.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val ptyReaderDispatcher: CoroutineDispatcher = Dispatchers.IO
