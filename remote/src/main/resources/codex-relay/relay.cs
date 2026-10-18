// Hey Mike. Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
// A private, memory-only stdio relay. SSH clients may detach; Codex keeps its writer.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

public static class MikeCodexRelay {
    const int MaxBytes = 32 * 1024 * 1024;
    const long MaxBacklog = 64 * 1024 * 1024;
    static readonly Encoding Utf8 = new UTF8Encoding(false, true);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct StartupInfo {
        public int cb; public string reserved, desktop, title;
        public int x, y, width, height, charsX, charsY, fill, flags;
        public short show, reservedBytes; public IntPtr reservedPointer, input, output, error;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct ProcessInfo { public IntPtr process, thread; public int pid, threadId; }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CreateProcess(string application, StringBuilder command, IntPtr processAttributes,
        IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment,
        string directory, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

    // OpenSSH closes a kill-on-close job when its command exits. Start-Process
    // inherits that job. Break away explicitly, with a hidden private console
    // and NO inherited SSH pipe handles. Fail closed if Windows refuses it.
    public static int StartDetached(string executable, string arguments) {
        var startup = new StartupInfo { cb = Marshal.SizeOf(typeof(StartupInfo)), flags = 1, show = 0 };
        ProcessInfo process;
        var command = new StringBuilder(Arguments(new string[] { executable }) + " " + arguments);
        if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, false,
                0x01000000 | 0x00000010, IntPtr.Zero, null, ref startup, out process))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not start the independent computer relay");
        try { return process.pid; }
        finally { CloseHandle(process.thread); CloseHandle(process.process); }
    }

    public static bool Ready(int port, string token) {
        try {
            using (var socket = new TcpClient()) {
                if (!socket.ConnectAsync(IPAddress.Loopback, port).Wait(2000)) return false;
                socket.ReceiveTimeout = 2000; socket.SendTimeout = 2000;
                using (var stream = socket.GetStream())
                using (var reader = new StreamReader(stream, Utf8, false, 4096, true))
                using (var writer = new StreamWriter(stream, Utf8, 4096, true) { AutoFlush = true }) {
                    writer.WriteLine("V " + token);
                    return ReadFrame(reader) == "R1";
                }
            }
        } catch { return false; }
    }

    public static string Frame(string kind, long seq, byte[] bytes) {
        using (var sha = SHA256.Create()) {
            return kind + " " + seq + " " + bytes.Length + " " +
                Convert.ToBase64String(sha.ComputeHash(bytes)) + " " + Convert.ToBase64String(bytes);
        }
    }
    static byte[] Payload(string[] fields) {
        if (fields.Length != 5) throw new IOException("Invalid relay frame");
        int length;
        if (!int.TryParse(fields[2], out length) || length < 0 || length > MaxBytes)
            throw new IOException("Invalid relay frame length");
        var bytes = Convert.FromBase64String(fields[4]);
        using (var sha = SHA256.Create()) {
            if (bytes.Length != length || Convert.ToBase64String(sha.ComputeHash(bytes)) != fields[3])
                throw new IOException("Incomplete relay frame");
        }
        return bytes;
    }
    static string ReadFrame(StreamReader reader) {
        // ReadLine accepts an unfinished final line. A checksum is mandatory for payloads.
        var line = reader.ReadLine();
        if (line != null && line.Length > MaxBytes * 2) throw new IOException("Relay frame too large");
        return line;
    }
    sealed class Event {
        public long Seq; public string Line; public int Bytes;
    }

    public static void Host(string exe, string args, string token, string metadata, int leaseSeconds) {
        Host(exe, args, token, metadata, leaseSeconds, null, null);
    }

    // Windows CRT quoting, without cmd expansion of percent signs or shell syntax.
    public static string Arguments(string[] values) {
        var result = new StringBuilder();
        foreach (var value in values) {
            if (result.Length > 0) result.Append(' ');
            result.Append('"');
            int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') result.Append('\\', slashes * 2 + 1);
                else result.Append('\\', slashes);
                result.Append(c); slashes = 0;
            }
            result.Append('\\', slashes * 2); result.Append('"');
        }
        return result.ToString();
    }

    public static void Host(string exe, string args, string token, string metadata, int leaseSeconds,
                            string cwd, IDictionary environment) {
        var gate = new object();
        var inputs = new object();
        var backlog = new List<Event>();
        var receipts = new Dictionary<long, string>();
        long outputSeq = 0, inputSeq = 0, acknowledged = 0, backlogBytes = 0;
        Exception terminal = null;
        int readers = 2;
        var lastContact = DateTime.UtcNow;
        var child = new Process { StartInfo = new ProcessStartInfo(exe, args) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
            RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Utf8, StandardErrorEncoding = Utf8
        }};
        if (!String.IsNullOrEmpty(cwd)) child.StartInfo.WorkingDirectory = cwd;
        if (environment != null) foreach (DictionaryEntry entry in environment)
            child.StartInfo.EnvironmentVariables[(string)entry.Key] = (string)entry.Value;
        var listener = new TcpListener(IPAddress.Loopback, 0);
        try {
            child.Start();
            child.StandardInput.AutoFlush = true;
            listener.Start();
            // The child's identity lets a replacement relay of the same owner end it. No secret is written.
            File.WriteAllText(metadata, "{\"version\":1,\"port\":" +
                ((IPEndPoint)listener.LocalEndpoint).Port + ",\"pid\":" + Process.GetCurrentProcess().Id +
                ",\"startedTicks\":" + Process.GetCurrentProcess().StartTime.ToUniversalTime().Ticks +
                ",\"child\":" + child.Id + ",\"childTicks\":" + child.StartTime.ToUniversalTime().Ticks + "}", Utf8);
            // Process creation and metadata publication are startup, not offline time.
            // Start the client's lease only once the endpoint can be discovered.
            lastContact = DateTime.UtcNow;
            Action<string, StreamReader> collect = (kind, source) => {
                try {
                    string line;
                    while ((line = source.ReadLine()) != null) {
                        var bytes = Utf8.GetBytes(line + "\n");
                        if (bytes.Length > MaxBytes) throw new IOException("Codex output exceeded relay limit");
                        lock (gate) {
                            backlogBytes += bytes.Length;
                            if (backlogBytes > MaxBacklog) throw new IOException("Unread Codex output exceeded relay limit");
                            long seq = ++outputSeq;
                            backlog.Add(new Event { Seq = seq, Line = Frame(kind, seq, bytes), Bytes = bytes.Length });
                        }
                    }
                } catch (Exception e) { lock (gate) { terminal = e; } }
                finally { Interlocked.Decrement(ref readers); }
            };
            new Thread(() => collect("O", child.StandardOutput)) { IsBackground = true }.Start();
            new Thread(() => collect("D", child.StandardError)) { IsBackground = true }.Start();
            bool stop = false;
            while (!Volatile.Read(ref stop) && Volatile.Read(ref terminal) == null && (DateTime.UtcNow - lastContact).TotalSeconds < leaseSeconds) {
                if (!listener.Pending()) { Thread.Sleep(25); continue; }
                using (var socket = listener.AcceptTcpClient()) {
                    socket.NoDelay = true;
                    socket.ReceiveTimeout = 20000;
                    socket.SendTimeout = 120000;
                    using (var stream = socket.GetStream())
                    using (var reader = new StreamReader(stream, Utf8, false, 4096, true))
                    using (var writer = new StreamWriter(stream, Utf8, 4096, true) { AutoFlush = true }) {
                        var writes = new object();
                        Action<string> send = line => { lock (writes) { writer.WriteLine(line); } };
                        try {
                            var hello = (ReadFrame(reader) ?? "").Split(' ');
                            if (hello.Length == 2 && hello[0] == "V" && hello[1] == token) {
                                send(child.HasExited || Volatile.Read(ref terminal) != null ? "F Relay ended" : "R1");
                                continue;
                            }
                            if (hello.Length == 2 && hello[0] == "S" && hello[1] == token) {
                                try { if (!child.HasExited) child.Kill(); child.WaitForExit(5000); } catch { }
                                stop = true; send("X"); continue;
                            }
                            long cursor;
                            if (hello.Length != 3 || hello[0] != "H1" || hello[1] != token ||
                                !long.TryParse(hello[2], out cursor)) continue;
                            lock (gate) {
                                if (cursor < acknowledged || cursor > outputSeq) { send("F Replay cursor unavailable"); continue; }
                                acknowledged = cursor;
                                backlog.RemoveAll(e => { if (e.Seq > cursor) return false; backlogBytes -= e.Bytes; return true; });
                            }
                            lastContact = DateTime.UtcNow;
                            send("H1");
                            bool detached = false;
                            var receive = new Thread(() => {
                                bool delivering = false;
                                try {
                                    string line;
                                    while ((line = ReadFrame(reader)) != null) {
                                        var f = line.Split(' ');
                                        if (line == "P") { lastContact = DateTime.UtcNow; send("P"); continue; }
                                        if (line == "X") {
                                            try { if (!child.HasExited) child.Kill(); child.WaitForExit(5000); } catch { }
                                            stop = true; send("X"); break;
                                        }
                                        long seq;
                                        if (f.Length < 2 || !long.TryParse(f[1], out seq)) throw new IOException("Invalid relay sequence");
                                        if (f[0] == "A" && f.Length == 2) {
                                            lock (gate) {
                                                if (seq < acknowledged || seq > outputSeq) throw new IOException("Invalid output acknowledgement");
                                                acknowledged = seq;
                                                backlog.RemoveAll(e => { if (e.Seq > seq) return false; backlogBytes -= e.Bytes; return true; });
                                            }
                                        } else if (f[0] == "I") {
                                            var bytes = Payload(f);
                                            var text = Utf8.GetString(bytes);
                                            if (!text.EndsWith("\n") || text.IndexOf('\n') != text.Length - 1)
                                                throw new IOException("Input must be one complete JSON line");
                                            lock (inputs) {
                                                string hash;
                                                if (receipts.TryGetValue(seq, out hash)) {
                                                    if (hash != f[3]) throw new IOException("Conflicting input receipt");
                                                } else {
                                                    if (seq != inputSeq + 1) throw new IOException("Input sequence gap");
                                                    // If this write is uncertain, end THIS broker; never replay to a replacement child.
                                                    delivering = true;
                                                    child.StandardInput.Write(text);
                                                    child.StandardInput.Flush();
                                                    inputSeq = seq;
                                                    receipts[seq] = f[3];
                                                    if (seq > 4096) receipts.Remove(seq - 4096);
                                                    delivering = false;
                                                }
                                            }
                                            send("A " + seq);
                                        } else throw new IOException("Unknown relay frame");
                                        lastContact = DateTime.UtcNow;
                                    }
                                } catch (IOException) {
                                    if (delivering) terminal = new IOException("Codex input delivery is unknown");
                                    // Otherwise detach; keep this child and its receipts.
                                }
                                catch (Exception) { terminal = new IOException("Relay input failed; delivery is unknown"); }
                                finally { detached = true; }
                            }) { IsBackground = true };
                            receive.Start();
                            while (!Volatile.Read(ref detached) && !Volatile.Read(ref stop) && Volatile.Read(ref terminal) == null) {
                                Event[] events;
                                lock (gate) { events = backlog.FindAll(e => e.Seq > cursor).ToArray(); }
                                foreach (var e in events) { send(e.Line); cursor = e.Seq; }
                                if (child.HasExited && readers == 0) { send("E " + child.ExitCode); stop = true; break; }
                                if ((DateTime.UtcNow - lastContact).TotalSeconds >= leaseSeconds) { stop = true; break; }
                                Thread.Sleep(20);
                            }
                            socket.Close();
                            receive.Join(1000);
                        } catch (IOException) { /* A lost SSH attachment cannot kill the active task. */ }
                        catch (SocketException) { }
                    }
                }
            }
        } finally {
            listener.Stop();
            // Only the child launched above belongs to us. Never inspect or kill another writer.
            try { if (!child.HasExited) child.Kill(); child.WaitForExit(5000); } catch { }
            child.Dispose();
            File.WriteAllText(metadata, "{\"version\":1,\"port\":0,\"ended\":true}", Utf8);
        }
    }

    public static void Attach(int port, string token, long after) {
        using (var socket = new TcpClient()) {
            socket.Connect(IPAddress.Loopback, port);
            socket.NoDelay = true;
            using (var stream = socket.GetStream())
            using (var reader = new StreamReader(stream, Utf8, false, 4096, true))
            using (var writer = new StreamWriter(stream, Utf8, 4096, true) { AutoFlush = true }) {
                writer.WriteLine("H1 " + token + " " + after);
                var input = new Thread(() => {
                    try { string line; while ((line = Console.ReadLine()) != null) writer.WriteLine(line); }
                    catch { }
                    finally { socket.Close(); }
                }) { IsBackground = true };
                input.Start();
                string output;
                while ((output = ReadFrame(reader)) != null) { Console.WriteLine(output); Console.Out.Flush(); }
            }
        }
    }

    public static void Stop(int port, string token) {
        using (var socket = new TcpClient()) {
            socket.Connect(IPAddress.Loopback, port);
            socket.ReceiveTimeout = 10000;
            using (var stream = socket.GetStream())
            using (var reader = new StreamReader(stream, Utf8, false, 4096, true))
            using (var writer = new StreamWriter(stream, Utf8, 4096, true) { AutoFlush = true }) {
                writer.WriteLine("S " + token);
                if (ReadFrame(reader) != "X") throw new IOException("Relay stop was not confirmed");
            }
        }
    }
}
