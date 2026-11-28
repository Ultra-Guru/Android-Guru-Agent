// T81 native host-side verification harness（本地 Linux 真实 forkpty 验证，
// 不进 APK —— 仅用于 CI 之外的语法/行为回归。Android 上走
// NativePtyJniInstrumentationTest 真机 instrumentation）。
#include "pty_engine.h"
#include <android/log.h>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include <chrono>
#include <atomic>
#include <csignal>
#include <cerrno>

using namespace apex;

static int g_failures = 0;
#define CHECK(cond, msg) do { \
    if (cond) { printf("  [PASS] %s\n", msg); } \
    else { printf("  [FAIL] %s\n", msg); ++g_failures; } \
} while (0)

int main() {
    printf("== T81 native host verification ==\n");

    // ─── T81 N-2: close() 路径 exit code 保留 ───
    {
        printf("[N-2] exit code preserved via close()\n");
        PtyEngine& eng = PtyEngine::instance();
        // spawn `sh -c 'exit 42'` via argv path
        int id = eng.createSessionArgv({"/bin/sh", "-c", "exit 42"}, "", {}, 24, 80);
        CHECK(id > 0, "session created");
        // 等子进程退出（轮询 isAlive -> reapChild）
        bool exited = false;
        for (int i = 0; i < 200; ++i) {
            if (!eng.isAlive(id)) { exited = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(exited, "child exited within 1s");
        CHECK(eng.getExitCode(id) == 42, "exit code 42 preserved after natural exit");
        eng.closeSession(id);

        // T81 N-2 可观察修复点：signal 终止（session 仍在 map）→ 等死亡 → exitCode 可查。
        // closeSession 会把 session 从 map 摘除（NOT_FOUND → -1，语义如此），
        // 所以「经 close() 杀死」的 exitCode 只在 PtySession 内部一致；
        // 外部可观察路径 = 先 signal、后查询、再 close。
        int id2 = eng.createSessionArgv({"/bin/sh", "-c", "sleep 30"}, "", {}, 24, 80);
        CHECK(id2 > 0, "long-running session created");
        CHECK(eng.isAlive(id2), "long-running child alive");
        CHECK(eng.sendSignal(id2, SIGKILL), "SIGKILL sent to process group");
        bool died = false;
        for (int i = 0; i < 100; ++i) {
            if (!eng.isAlive(id2)) { died = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(died, "child died after SIGKILL");
        int ec = eng.getExitCode(id2);
        CHECK(ec == 137, "signal-death exit code == 137 (128+SIGKILL)");
        printf("    (signal-death exit code = %d)\n", ec);
        eng.closeSession(id2);
    }

    // ─── T81 N-1: 多 session 并发 IO 不被全局锁串行化 ───
    {
        printf("[N-1] concurrent multi-session IO\n");
        PtyEngine& eng = PtyEngine::instance();
        std::vector<int> ids;
        for (int i = 0; i < 20; ++i) {
            int id = eng.createSessionArgv({"/bin/sh", "-i"}, "", {}, 24, 80);
            if (id > 0) ids.push_back(id);
        }
        CHECK(ids.size() == 20, "20 sessions created");
        // 20 线程并发 readEx（EAGAIN → NO_DATA 立即返回）—— 若仍持全局锁，
        // 单次 readEx 无数据时也应快速返回；真正要验证的是 write 并发不互相阻塞。
        std::atomic<int> done{0};
        std::vector<std::thread> writers;
        auto t0 = std::chrono::steady_clock::now();
        for (int id : ids) {
            writers.emplace_back([&eng, id, &done]() {
                // 写一条命令（session 的 master 非 EAGAIN 时快速完成）
                eng.write(id, "echo t81concurrent\n", strlen("echo t81concurrent\n"));
                // 再做一次有界 readEx
                eng.readEx(id, 4096);
                done.fetch_add(1);
            });
        }
        for (auto& w : writers) w.join();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        CHECK(done.load() == 20, "all 20 concurrent writers finished");
        printf("    (20 sessions × write+readEx took %lld ms)\n", (long long)ms);
        for (int id : ids) eng.closeSession(id);
        CHECK(eng.activeCount() == 0, "all closed, no native leak");
    }

    // ─── T81 N-4: close() 不永久阻塞（有界回收） ───
    {
        printf("[N-4] close() bounded, idempotent, double-close safe\n");
        PtyEngine& eng = PtyEngine::instance();
        int id = eng.createSessionArgv({"/bin/sh", "-c", "sleep 300"}, "", {}, 24, 80);
        auto t0 = std::chrono::steady_clock::now();
        eng.closeSession(id);
        eng.closeSession(id);  // double close must be a no-op
        eng.closeSession(id);  // triple close safe
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        CHECK(ms < 2000, "close bounded (<2s, was potentially unbounded)");
        printf("    (close+double-close took %lld ms)\n", (long long)ms);
    }

    // ─── EOF / EIO 语义保持（P70 回归）───
    {
        printf("[P70 regression] EOF semantics after child exit\n");
        PtyEngine& eng = PtyEngine::instance();
        int id = eng.createSessionArgv({"/bin/sh", "-c", "printf done"}, "", {}, 24, 80);
        // drain until EOF
        ReadOutcome out;
        int eofSeen = 0;
        for (int i = 0; i < 300; ++i) {
            out = eng.readEx(id, 65536);
            if (out.status == PTY_READ_EOF) { eofSeen = 1; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        CHECK(eofSeen == 1, "EOF reported after output drained");
        // T81 N-2 补强：readEx 的 EOF/EIO 分支顺手 reap —— 无需 isAlive 轮询即可查
        CHECK(eng.getExitCode(id) == 0, "exit 0 queryable immediately after EOF");
        CHECK(!eng.isAlive(id), "alive==false after EOF (reaped)");
        eng.closeSession(id);
    }

    // ─── signal 送达整个进程组（回归）───
    {
        printf("[regression] signal to process group\n");
        PtyEngine& eng = PtyEngine::instance();
        // sh -c 'sleep 300 & wait' → 前台是 wait（同 pgid）
        int id = eng.createSessionArgv({"/bin/sh", "-c", "sleep 300; echo never"}, "", {}, 24, 80);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        CHECK(eng.sendSignal(id, SIGTERM), "SIGTERM delivered");
        bool exited = false;
        for (int i = 0; i < 100; ++i) {
            if (!eng.isAlive(id)) { exited = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(exited, "process group terminated by SIGTERM");
        eng.closeSession(id);
    }

    // ─── T82: 前台作业组信号（Ctrl-C 语义 —— shell 存活） ───
    {
        printf("[T82] foreground-group-only signal keeps shell alive\n");
        PtyEngine& eng = PtyEngine::instance();
        // 交互 bash + 一个前台 sleep。注意：native 安全默认 PATH 是 Android 侧
        //（/system/bin…），在 Linux host 上必须显式传 PATH —— 否则 bash 找不到
        // sleep，作业永远不成立（生产 LocalShellBackend 显式传同一组 env，同理）。
        std::vector<std::pair<std::string, std::string>> hostEnv{{"PATH", "/usr/bin:/bin:/usr/sbin:/sbin"}};
        int id = eng.createSessionArgv({"/bin/bash", "-i"}, "", hostEnv, 24, 80);
        CHECK(id > 0, "interactive session created");
        // 给 bash 一点时间完成初始化（prompt / job-control 启用）
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        const char* cmd = "sleep 300\n";
        eng.write(id, cmd, strlen(cmd));
        // 等 sleep 成为前台作业（作业控制：tcgetpgrp != shell pid）
        bool fgJob = false;
        for (int i = 0; i < 200; ++i) {
            if (eng.signalForeground(id, SIGTERM)) { fgJob = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(fgJob, "foreground job detected and signaled");
        // shell 必须仍然存活（fg-scoped 信号绝不杀 shell）
        bool shellAlive = true;
        for (int i = 0; i < 100; ++i) {
            if (!eng.isAlive(id)) { shellAlive = false; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(shellAlive, "shell SURVIVES foreground-scoped SIGTERM");
        // 空闲状态（作业已死、shell 回 prompt）→ signalForeground 应拒绝（false）
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        bool refusedAtIdle = false;
        for (int i = 0; i < 100; ++i) {
            if (!eng.signalForeground(id, SIGTERM)) { refusedAtIdle = true; break; }
            // shell 可能还没回到 prompt（fg 仍是旧作业组）—— 继续等
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        CHECK(refusedAtIdle, "no-foreground-job signal refused at idle prompt");
        eng.closeSession(id);
    }

    // ── #842：setsid 守护孙进程清扫（close 的 /proc 后代快照 + 补杀）────
    // 事故形态：孙进程经 setsid 自立 session/pgid —— kill(-fg)/kill(-pid_)
    // 两组信号均不可达，旧实现 close 后守护进程残活（泄漏 CPU/内存直至重启）。
    // 修复：close() 信号梯度**前**经 /proc children 快照后代，梯度后逐 pid
    // SIGKILL 兜底。本测试在 Linux 主机验证（Android 内核同样提供 /proc
    // children —— CONFIG_PROC_CHILDREN 标配）。
    {
        printf("[#842] setsid grandchild sweep on close\n");
        PtyEngine& eng = PtyEngine::instance();
        // 交互 bash（作业控制 shell 常驻）—— 与 T82 测试同款夹具：Linux host
        // 必须显式传 PATH（native 安全默认 PATH 是 Android 侧）。
        std::vector<std::pair<std::string, std::string>> hostEnv{{"PATH", "/usr/bin:/bin:/usr/sbin:/sbin"}};
        int id = eng.createSessionArgv({"/bin/bash", "-i"}, "", hostEnv, 24, 80);
        CHECK(id > 0, "interactive session created");
        // bash 初始化（prompt / 作业控制）
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        // 守护孙进程（#842 的可清扫逃逸形态）：后台作业 bash -c 的**子进程
        // python 自行 setsid**（非组长 → 成功；直接后台作业自己 setsid 会因
        // 作业控制组长身份 EPERM）。python 有自有 session/pgid —— kill(-fg)
        // /kill(-pid_) 两组信号全部不可达；但其父 bash -c 存活等待，/proc
        // children 树（shell → bash -c → python）仍可达 —— 正是清扫目标形态。
        // （util-linux setsid 二进制派的孤儿被 init 收养，任何 pid 树清扫都
        // 不可达 —— 本清扫不承诺该形态，与 Termux 同款边界。）
        const char* pidFile = "/tmp/apex_setsid_sweep_pid";
        const char* daemonScript = "/tmp/apex_setsid_daemon.py";
        unlink(pidFile);
        // 守护脚本由测试直写（避开 pty 引号转义矩阵）：进程自行 setsid 后
        // 落 pid 文件并长眠 —— bash 的直接子进程（/proc children 可达）。
        {
            FILE* f = fopen(daemonScript, "w");
            CHECK(f != nullptr, "daemon script writable");
            if (f != nullptr) {
                fprintf(f, "import os, sys, time\n");
                fprintf(f, "os.setsid()\n");
                fprintf(f, "open(sys.argv[1], 'w').write(str(os.getpid()))\n");
                fprintf(f, "time.sleep(300)\n");
                fclose(f);
            }
        }
        char cmd[512];
        // `; :` 尾缀防 bash -c 单命令 exec 优化（否则 python 顶替作业组长
        // 身份，setsid EPERM）；复合命令强制真 fork —— python 为组长之子。
        snprintf(cmd, sizeof(cmd),
                 "bash -c 'python3 %s %s; :' >/dev/null 2>&1 &\n",
                 daemonScript, pidFile);
        eng.write(id, cmd, strlen(cmd));
        // 等 pid 文件落地（守护进程已起）。
        int daemonPid = -1;
        for (int i = 0; i < 200; ++i) {
            FILE* f = fopen(pidFile, "r");
            if (f != nullptr) {
                if (fscanf(f, "%d", &daemonPid) != 1) daemonPid = -1;
                fclose(f);
                if (daemonPid > 0) break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(daemonPid > 0, "setsid grandchild spawned and reported pid");
        if (daemonPid > 0) {
            CHECK(kill(daemonPid, 0) == 0, "grandchild alive before close");
            eng.closeSession(id);
            // close 内的补杀有界等待后应已生效 —— 有界重试（最多 2s）容忍调度抖动。
            bool reaped = false;
            for (int i = 0; i < 400; ++i) {
                if (kill(daemonPid, 0) != 0 && errno == ESRCH) { reaped = true; break; }
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            }
            CHECK(reaped, "setsid grandchild reaped by close() sweep");
            unlink(pidFile);
        } else {
            eng.closeSession(id);
        }
    }

    printf("== %s (%d failures) ==\n", g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures);
    return g_failures == 0 ? 0 : 1;
}
