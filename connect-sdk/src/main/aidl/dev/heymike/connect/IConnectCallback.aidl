package dev.heymike.connect;
import dev.heymike.connect.ConnectMessage;
oneway interface IConnectCallback {
    void onNotification(in ConnectMessage message);
    void onResponse(in ConnectMessage message);
}
