package cn.niuyannet.kaochang.android.mqtt

object MqttProtocol {
    const val ACTION_ORDER_EVENT = 1
    const val ACTION_SERVICE_STATE = 2
    const val ACTION_DEVICE_LOG = 3

    const val TYPE_RUNTIME_CONTROL = "runtime_control"
    const val TYPE_CONCURRENCY_CONTROL = "concurrency_control"
    const val TYPE_RUNTIME_STATE = "runtime_state"
    const val TYPE_RUNTIME_STATE_ACK = "runtime_state_ack"
    const val TYPE_AVAILABLE_STATE = "available_state"
    const val TYPE_AVAILABLE_STATE_ACK = "available_state_ack"
    const val TYPE_RUNTIME_CONTROL_ACK = "runtime_control_ack"
    const val TYPE_CONCURRENCY_CONTROL_ACK = "concurrency_control_ack"

    const val SOURCE_MQTT_ACTION_2 = "mqtt-action=2"
}
