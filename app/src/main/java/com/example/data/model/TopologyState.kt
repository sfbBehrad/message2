package com.example.data.model

/**
 * End-to-End network topology status across the 3 communication legs:
 * Leg A: Local Link  (Phone 1 <-> ESP Node 1)
 * Leg B: Radio Link  (ESP Node 1 <-> ESP Node 2 via NRF24L01 Ch 115)
 * Leg C: Remote Link (ESP Node 2 <-> Phone 2 via Bluetooth)
 */
data class TopologyState(
    val localLinkActive: Boolean = false,
    val radioLinkActive: Boolean = false,
    val remoteLinkActive: Boolean = false,
    val lastPingSentTime: Long = 0L,
    val lastPongReceivedTime: Long = 0L,
    val roundTripLatencyMs: Long = 0L,
    val pingsSent: Int = 0,
    val pongsReceived: Int = 0,
    val remoteNodeName: String = "ESP_Node_2",
    val localNodeName: String = "ESP_Node_1",
    val rfChannel: Int = 115,
    val rfDataRate: String = "1 MBPS",
    val rfPower: String = "PA_MAX"
) {
    val isE2EConnected: Boolean
        get() = localLinkActive && radioLinkActive && remoteLinkActive

    val statusSummary: String
        get() = when {
            !localLinkActive -> "Local BT Disconnected"
            !radioLinkActive -> "Radio Link Lost (ESP1 <-> ESP2)"
            !remoteLinkActive -> "Remote Phone Offline (Radio Link OK)"
            else -> "E2E Mesh Connected"
        }

    val statusLevel: StatusLevel
        get() = when {
            !localLinkActive -> StatusLevel.ERROR
            !radioLinkActive -> StatusLevel.WARNING
            !remoteLinkActive -> StatusLevel.WARNING
            else -> StatusLevel.OK
        }

    enum class StatusLevel {
        OK,
        WARNING,
        ERROR
    }
}
