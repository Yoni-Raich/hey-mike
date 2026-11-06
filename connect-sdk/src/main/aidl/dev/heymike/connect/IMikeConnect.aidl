package dev.heymike.connect;
import dev.heymike.connect.ConnectMessage;
import dev.heymike.connect.IConnectCallback;
interface IMikeConnect {
    int bindingLevel();
    oneway void send(in ConnectMessage request, IConnectCallback callback);
    oneway void cancel(String jsonRpcId);
}
