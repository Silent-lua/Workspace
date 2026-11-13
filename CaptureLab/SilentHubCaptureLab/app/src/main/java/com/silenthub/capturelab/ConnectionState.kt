package com.silenthub.capturelab

enum class ConnectionState {
    DISCONNECTED,
    DETECTING,
    CONNECTING,
    CONNECTED_WAITING,
    PAIRED,
    RECONNECTING,
    CONNECTION_FAILED
}