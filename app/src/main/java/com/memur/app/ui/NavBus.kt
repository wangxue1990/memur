package com.memur.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** 深链与跨页面导航事件总线 */
object NavBus {
    /** 形如 "note/12" 或 "reminders" */
    val deepLink = MutableStateFlow<String?>(null)
}
