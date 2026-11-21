// T91 (D2-D4): Terminal push-based event callback (Operit terminal-core parity).
//
// oneway — the service never blocks on client dispatch; binder's async
// transaction buffer provides natural backpressure (a slow client slows its
// own delivery, never the PTY output pump).
package com.apex.agent.platform.terminal.service;

oneway interface ITerminalCallback {
    // Incremental PTY output chunk (UTF-8 bytes, in-order per session).
    void onOutput(long sessionId, in byte[] data);

    // Session shell/process tree ended (cause: NORMAL / USER / BROKEN ...).
    void onExit(long sessionId, int exitCode, String cause);

    // Session lifecycle transition (RUNNING / WAITING_INPUT / EXITED ...).
    void onSessionStateChanged(long sessionId, String state);
}
