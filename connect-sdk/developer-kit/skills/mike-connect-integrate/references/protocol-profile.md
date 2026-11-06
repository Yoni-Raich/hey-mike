# Mike Connect v1 wire profile

Prefer `MikeConnectService` over reimplementing the binding. This reference is
for interoperability/debugging, not a reason to bypass the SDK's trust checks.

Discovery uses an exported Android service with action
`dev.heymike.connect.action.CONNECT` and metadata `dev.heymike.connect.binding=1`.
The SDK AIDL contract is `dev.heymike.connect.IMikeConnect` with asynchronous
`send(ConnectMessage, IConnectCallback)` and `cancel(String)` calls. The service
verifies caller identity for both. Find the AIDL sources in the SDK source JAR
when building a bridge. JSON-RPC request IDs and stable effect request IDs are
different identifiers; cancel uses the original RPC ID string.

The JSON profile uses MCP revision `2026-07-28`. It supports `tools/list` and
`tools/call`, not a full generic MCP server/transport. Every request includes
protocol/client metadata under `params._meta`. Example call (SDK normally
constructs this):

```json
{
  "jsonrpc": "2.0",
  "id": "rpc-17",
  "method": "tools/call",
  "params": {
    "name": "set_accent",
    "arguments": {"accent": "amber"},
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientInfo": {"name": "Hey Mike", "version": "1.0.0"},
      "io.modelcontextprotocol/clientCapabilities": {"elicitation": {"form": {}}},
      "dev.heymike/origin": "chat",
      "dev.heymike/requestId": "stable-write-17",
      "dev.heymike/userApproved": false,
      "progressToken": "stable-write-17"
    }
  }
}
```

The host sets approval metadata only after its approval flow. Never let a model
or provider response synthesize approval. An ordinary write can run under a
previously granted capability without the sensitive approval flag.

`tools/list` returns descriptors with name/title/description, inputSchema,
optional outputSchema, annotations and namespaced `_meta` fields for access,
UI mode, summary and timeout. `tools/call` returns a `complete` result with
`structuredContent` and `content`, or `input_required` with a short-lived
`requestState` and one string/choice form field. The host supports at most two
question rounds. Only the real user's answer enters `inputResponses`.

Errors use JSON-RPC error envelopes for invalid/unsupported wire requests, or
`isError:true` and namespaced code/`mayHaveRun` metadata for failed operations.
Do not equate a timeout or lost binding with a safe failed write. Keep the
original stable request ID and inspect state instead of creating a new effect.

Large resource/blob transfer, HTTP/cloud transport, general subscriptions,
app-event automations and cross-profile execution are not in this v1 profile.
App descriptions/results remain untrusted data for the calling agent.
