// T91 (D2-D4): Terminal IPC service contract — the cross-process terminal
// access boundary (Operit terminal-core parity).
//
// Transport notes (see docs/terminal/TERMINAL_IPC_SERVICE_T91.md):
//  - sessionId is the TerminalRuntime's Long id (decimal form over the wire);
//  - createSession returns the decimal sessionId on success, or an
//    "ERR:<message>" line on failure (one round-trip, no binder exception
//    reliance — the runtime result mapping is unit-tested in the controller);
//  - listSessions() returns a JSON array of session summaries;
//  - output streaming is push-based: register a callback once, then receive
//    onOutput chunks (event-driven via the TerminalEventBus, never polled).
//
// Shape freeze rule (PR #60 discipline): additive only — new methods and new
// optional params keep old clients working; existing semantics never change.
package com.apex.agent.platform.terminal.service;

import com.apex.agent.platform.terminal.service.ITerminalCallback;

interface ITerminalService {
    // Returns decimal sessionId, or "ERR:<message>" on failure.
    String createSession(String backendId, int rows, int cols, String cwd, in List<String> envAssignments);

    // Raw UTF-8 bytes straight to the PTY (paste / key sequences — no
    // String<->charset round trip, T85 discipline).
    void write(long sessionId, in byte[] data);

    // Raw text convenience path (line content must be newline-terminated by
    // the caller when a shell line is intended).
    void writeText(long sessionId, String text);

    void resize(long sessionId, int rows, int cols);

    // force=true closes even with running jobs (SIGTERM -> grace -> SIGKILL).
    void closeSession(long sessionId, boolean force);

    // JSON array: [{"id":1,"state":"RUNNING","alive":true}, ...]
    String listSessions();

    // Liveness + contract version probe ("pong:<TerminalApiVersion>").
    String ping();

    void registerCallback(ITerminalCallback callback);

    void unregisterCallback(ITerminalCallback callback);
}
