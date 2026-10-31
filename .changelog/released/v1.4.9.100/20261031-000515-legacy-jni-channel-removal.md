<!-- pr: legacy-jni-channel-removal -->

- PTY 旧 String JNI 通道整链移除：NativePty 的 nativeWrite/nativeWriteRaw/nativeRead 三个 @Deprecated external fun + jni_bridge.cpp 对应三个 JNIEXPORT + PtyEngine/PtySession 的 writeLine/read 死链 + 孤儿头文件 ansi_filter.h（生产自 P70 起一律走 nativeWriteBytes/nativeReadBytes 字节通道，旧行为缺陷：双换行/NUL 截断/无法区分 idle 与 EOF）
