# JNI names and callbacks are part of the native contract.
-keep class dev.androidagent.workspace.NativeDeltaApplier { native <methods>; }
-keep class dev.androidagent.workspace.NativeAtomicPublisher { native <methods>; }
-keep class dev.androidagent.workspace.NativeDeltaApplier$Control { boolean cancelled(); void progress(long,long); }
