"""Forward MCP packets once over the SSH loopback. Keep stdin live during tool calls."""
import concurrent.futures
import http.client
import os
import sys
import threading

port = int(os.environ["MIKE_MCP_PORT"])
headers = {"Authorization": "Bearer " + os.environ["MIKE_MCP_TOKEN"], "Content-Type": "application/json"}
output_lock = threading.Lock()


def forward(line):
    connection = http.client.HTTPConnection("127.0.0.1", port)
    try:
        connection.request("POST", "/mcp", line, headers)
        reply = connection.getresponse()
        data = reply.read()
        if reply.status not in (200, 202):
            raise OSError("MCP transport failed")
        if data.strip():
            with output_lock:
                sys.stdout.buffer.write(data.rstrip() + b"\n")
                sys.stdout.buffer.flush()
    except Exception:
        # Transport loss can follow a committed tool call. Never resend it.
        os._exit(1)
    finally:
        connection.close()


with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
    for packet in sys.stdin.buffer:
        pool.submit(forward, packet)
    # The ACP parent closed stdio. No caller can receive an outstanding reply.
    os._exit(0)
