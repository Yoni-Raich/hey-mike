# JSch creates its ciphers, key exchanges and signatures from class names in
# its config, so a shrinker must keep them.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
