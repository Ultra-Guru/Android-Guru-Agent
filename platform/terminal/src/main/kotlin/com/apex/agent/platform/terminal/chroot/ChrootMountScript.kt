package com.apex.agent.platform.terminal.chroot

/**
 * T99: rootfs-scoped mount protocol for the su shell. The users lock is shared
 * throughout the guest lifetime; the operations lock serializes mount changes.
 * If flock or mountinfo is unavailable, fail closed rather than stacking binds.
 * Only mounts whose mount IDs we recorded may be detached. Old unmarked T98
 * mounts are reused but deliberately not swept: they may belong to another user.
 */
internal object ChrootMountScript {
    fun header(rootfsDir: String, mountInfoPath: String = "/proc/self/mountinfo"): String = listOf(
        "MI=${shellQuote(mountInfoPath)}",
        "MI_R=${shellQuote(mountInfoEscape(rootfsDir))}",
        "STATE=\"\$R/.t99-chroot-mounts\"",
        "command -v flock >/dev/null 2>&1 || { printf '%s\\n' 'ChrootError:MOUNT_GUARD_UNAVAILABLE (flock)' >&2; exit 1; }",
        "[ -r \"\$MI\" ] || { printf '%s\\n' 'ChrootError:MOUNT_GUARD_UNAVAILABLE (mountinfo)' >&2; exit 1; }",
        "[ ! -L \"\$STATE\" ] && mkdir -p \"\$STATE\" || exit 1",
        "[ ! -L \"\$R/.t99-chroot-users.lock\" ] && [ ! -L \"\$R/.t99-chroot-ops.lock\" ] || exit 1",
        "exec 9>>\"\$R/.t99-chroot-users.lock\" || exit 1",
        "flock -s 9 || exit 1",
        "exec 8>>\"\$R/.t99-chroot-ops.lock\" || exit 1",
        "flock -x 8 || exit 1",
        "mount_id() {",
        "  [ -r \"\$MI\" ] || return 1",
        "  mi_found=; mi_seen=0",
        "  while IFS=' ' read -r mi_id mi_parent mi_device mi_root mi_target mi_options mi_rest; do",
        "    case \"\$mi_id\" in ''|*[!0-9]*) return 1 ;; esac",
        "    mi_seen=1",
        "    if [ \"\$mi_target\" = \"\$1\" ]; then mi_found=\"\$mi_id\"; fi",
        "  done < \"\$MI\"",
        "  [ \"\$mi_seen\" = 1 ] || return 1",
        "  printf '%s' \"\$mi_found\"",
        "}",
        "ensure_mount() {",
        "  guard_name=\"\$1\"; shift",
        "  guard_target=\"\$R/\$guard_name\"",
        "  guard_marker=\"\$STATE/\$guard_name\"",
        "  [ ! -L \"\$guard_marker\" ] || return 1",
        "  guard_current=\"\$(mount_id \"\$MI_R/\$guard_name\")\" || return 1",
        "  [ -z \"\$guard_current\" ] || return 0",
        "  \"\$@\" \"\$guard_target\" 2>/dev/null || return 1",
        "  guard_current=\"\$(mount_id \"\$MI_R/\$guard_name\")\" || return 1",
        "  [ -n \"\$guard_current\" ] || return 1",
        "  printf '%s\\n' \"\$guard_current\" > \"\$guard_marker\" || return 1",
        "}",
        "cleanup_one() {",
        "  clean_name=\"\$1\"",
        "  clean_marker=\"\$STATE/\$clean_name\"",
        "  [ ! -L \"\$clean_marker\" ] && [ -f \"\$clean_marker\" ] || return 0",
        "  IFS= read -r clean_expected < \"\$clean_marker\" || return 0",
        "  case \"\$clean_expected\" in ''|*[!0-9]*) return 0 ;; esac",
        "  clean_current=\"\$(mount_id \"\$MI_R/\$clean_name\")\" || return 0",
        "  if [ \"\$clean_expected\" = \"\$clean_current\" ]; then",
        "    umount \"\$R/\$clean_name\" 2>/dev/null && rm -f \"\$clean_marker\"",
        "  elif [ -z \"\$clean_current\" ]; then",
        "    rm -f \"\$clean_marker\"",
        "  fi",
        "  return 0",
        "}",
        "cleanup_mounts() {",
        "  clean_rc=\$?",
        "  trap - EXIT HUP INT TERM",
        "  flock -u 8 2>/dev/null",
        "  flock -u 9 2>/dev/null",
        // Nonblocking upgrade: another active session still uses the shared mounts.
        // The last session (or a subsequent session after SIGKILL) owns cleanup.
        "  if flock -n -x 9 2>/dev/null; then",
        "    if flock -x 8 2>/dev/null; then",
        "      cleanup_one sdcard",
        "      cleanup_one workspace",
        "      cleanup_one root",
        "      cleanup_one dev",
        "      cleanup_one sys",
        "      cleanup_one proc",
        "      flock -u 8",
        "    fi",
        "    flock -u 9",
        "  fi",
        "  exit \"\$clean_rc\"",
        "}",
        "trap 'cleanup_mounts' EXIT",
        "trap 'exit 129' HUP",
        "trap 'exit 130' INT",
        "trap 'exit 143' TERM"
    ).joinToString("\n")

    fun mount(name: String, vararg command: String): String =
        "ensure_mount ${shellQuote(name)} ${command.joinToString(" ") { shellQuote(it) }} || exit 1"

    const val releaseOperationsLock = "flock -u 8 || exit 1"

    /** mountinfo escapes whitespace and backslash as octal sequences. */
    private fun mountInfoEscape(path: String): String = path
        .replace("\\", "\\134")
        .replace(" ", "\\040")
        .replace("\t", "\\011")
        .replace("\n", "\\012")
}
