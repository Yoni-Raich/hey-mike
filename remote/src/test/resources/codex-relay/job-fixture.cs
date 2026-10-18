// Hey Mike. Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
// A Windows OpenSSH-style kill-on-close job. Fixture processes only.
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class MikeRelayJobFixture {
    [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
        public long processTime, jobTime; public uint flags;
        public UIntPtr minimum, maximum; public uint active;
        public UIntPtr affinity; public uint priority, scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters {
        public ulong readOps, writeOps, otherOps, readBytes, writeBytes, otherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct Limits {
        public BasicLimits basic; public IoCounters io;
        public UIntPtr processMemory, jobMemory, peakProcess, peakJob;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct Startup {
        public int cb; public string reserved, desktop, title;
        public int x, y, width, height, charsX, charsY, fill, flags;
        public short show, reservedBytes; public IntPtr reservedPointer, input, output, error;
    }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
        public IntPtr process, thread; public uint pid, threadId;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int kind, ref Limits limits, int length);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CreateProcess(string application, StringBuilder command, IntPtr processAttributes,
        IntPtr threadAttributes, bool inherit, uint flags, IntPtr environment, string directory,
        ref Startup startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint timeout);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process, uint code);

    public static uint Run(string command) {
        var job = CreateJobObject(IntPtr.Zero, null);
        var limits = new Limits { basic = new BasicLimits { flags = 0x2000 | 0x800 } };
        var startup = new Startup { cb = Marshal.SizeOf(typeof(Startup)), flags = 1, show = 0 };
        ProcessInfo process = new ProcessInfo();
        try {
            if (job == IntPtr.Zero || !SetInformationJobObject(job, 9, ref limits, Marshal.SizeOf(typeof(Limits))))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            if (!CreateProcess(null, new StringBuilder(command), IntPtr.Zero, IntPtr.Zero, false,
                    0x4 | 0x10, IntPtr.Zero, null, ref startup, out process))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            if (!AssignProcessToJobObject(job, process.process)) {
                TerminateProcess(process.process, 1);
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            ResumeThread(process.thread);
            if (WaitForSingleObject(process.process, 25000) != 0)
                throw new TimeoutException("Fixture bootstrap timed out");
            uint code;
            if (!GetExitCodeProcess(process.process, out code)) throw new Win32Exception();
            return code;
        } finally {
            // This closes ONLY the job and handles created above. Its surviving
            // children are killed, exactly the condition the relay must escape.
            if (job != IntPtr.Zero) CloseHandle(job);
            if (process.thread != IntPtr.Zero) CloseHandle(process.thread);
            if (process.process != IntPtr.Zero) CloseHandle(process.process);
        }
    }
}
