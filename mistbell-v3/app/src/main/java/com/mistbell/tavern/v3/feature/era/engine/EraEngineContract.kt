package com.mistbell.tavern.v3.feature.era.engine

/**
 * 引擎组件（独立 APK）的绑定契约。
 *
 * Mistbell 只通过两样东西和引擎说话：
 * 1. Binder 传「游戏 ZIP 的文件描述符 + 端口」，引擎解包并准备；
 * 2. 回环 TCP 上的 EWP/0.3 JSON-Lines（引擎出事件，前端回输入）。
 *
 * 这样即使以后把引擎收进主进程（AAR/JNI），前端代码也不用改。
 */
internal object EraEngineContract {
    const val ENGINE_PACKAGE = "com.mistbell.era"
    const val SERVICE_CLASS = "com.mistbell.era.EraEngineService"
    const val CONTROL_PERMISSION = "com.mistbell.era.permission.CONTROL"

    const val EXTRA_REPLY_TO = "replyTo"
    const val EXTRA_PORT = "port"
    const val EXTRA_GAME_NAME = "gameName"
    const val EXTRA_PACK_VERSION = "packVersion"
    const val EXTRA_ZIP_FD = "zipFd"

    const val MSG_STATUS = 1
    const val MSG_STOP = 2

    const val STATE_PREPARING = "preparing"
    const val STATE_IMPORTING = "importing"
    const val STATE_IMPORTED = "imported"
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_STOPPED = "stopped"
    const val STATE_ERROR = "error"
}
