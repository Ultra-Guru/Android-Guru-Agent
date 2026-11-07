package com.apex.agent.platform.terminal.chroot

import java.io.File

/**
 * T99: rootfs-scoped mount protocol for the su shell. The users lock is shared
 * by every guest descendant; the operations lock serializes mount changes.
 * Locks and mount markers live outside the guest's /, paths are canonicalized,
 * targets are rejected when they are symlinks, and a lost marker rolls its own
 * bind back. Only recorded mount IDs are detached; unowned T98 mounts are never
 * swept: they may belong to another user.
 */
internal object ChrootMountScript {
    fun header(rootfsDir: String, mountInfoPath: String = "/proc/self/mountinfo"): String {
        // The kernel reports physical mountinfo paths, not /data/user/0 aliases.
        // Resolve again under su; changed symlinks or namespaces fail closed.
        val canonical = File(rootfsDir).canonicalPath
        val parent = File(canonical).parentFile ?: File("/")
        val stateBase = File(parent, ".t99-chroot-state")
        val state = File(stateBase, File(canonical).name)
        return listOf(
        "MI=${shellQuote(mountInfoPath)}",
        "MI_R=${shellQuote(mountInfoEscape(canonical))}",
        "EXPECTED_R=${shellQuote(canonical)}",
        "R=\$(cd -P \"\$R\" && pwd -P) || exit 1",
        "[ \"\$R\" = \"\$EXPECTED_R\" ] && [ \"\$R\" != / ] || exit 1",
        "STATE_BASE=${shellQuote(stateBase.absolutePath)}",
        "STATE=${shellQuote(state.absolutePath)}",
        "command -v flock >/dev/null 2>&1 || { printf '%s\\n' 'ChrootError:MOUNT_GUARD_UNAVAILABLE (flock)' >&2; exit 1; }",
        "[ -r \"\$MI\" ] || { printf '%s\\n' 'ChrootError:MOUNT_GUARD_UNAVAILABLE (mountinfo)' >&2; exit 1; }",
        // State is a sibling of the rootfs, never guest-visible.
        "[ ! -L \"\$STATE_BASE\" ] && [ ! -L \"\$STATE\" ] || exit 1",
        "umask 077",
        "mkdir -p \"\$STATE\" || exit 1",
        "[ ! -L \"\$STATE_BASE\" ] && [ ! -L \"\$STATE\" ] || exit 1",
        "[ ! -L \"\$STATE/users.lock\" ] && [ ! -L \"\$STATE/ops.lock\" ] || exit 1",
        "exec 9>>\"\$STATE/users.lock\" || exit 1",
        "flock -s 9 || exit 1",
        "exec 8>>\"\$STATE/ops.lock\" || exit 1",
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
        "rollback_mount() {",
        "  rollback_id=\"\$2\"",
        "  rollback_current=\"\$(mount_id \"\$MI_R/\$1\")\" || return 1",
        "  [ -n \"\$rollback_id\" ] && [ \"\$rollback_current\" = \"\$rollback_id\" ] || return 1",
        "  umount \"\$R/\$1\" 2>/dev/null",
        "}",
        "ensure_mount() {",
        "  guard_name=\"\$1\"; shift",
        "  guard_target=\"\$R/\$guard_name\"",
        "  guard_marker=\"\$STATE/\$guard_name\"",
        // mount follows symlinks: reject even when the link points outside rootfs.
        "  [ ! -L \"\$guard_target\" ] && [ -d \"\$guard_target\" ] || return 1",
        "  [ ! -L \"\$guard_marker\" ] || return 1",
        "  guard_current=\"\$(mount_id \"\$MI_R/\$guard_name\")\" || return 1",
        "  [ -z \"\$guard_current\" ] || return 0",
        "  \"\$@\" \"\$guard_target\" 2>/dev/null || return 1",
        "  guard_current=\"\$(mount_id \"\$MI_R/\$guard_name\")\" || return 1",
        "  [ -n \"\$guard_current\" ] || return 1",
        // Without a marker the EXIT trap cannot see our bind. Roll it back
        // only if its newly observed mount ID is still the topmost mount.
        "  printf '%s\\n' \"\$guard_current\" > \"\$guard_marker\" || {",
        "    rollback_mount \"\$guard_name\" \"\$guard_current\"",
        "    return 1",
        "  }",
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
        // Unlocking 9 would also unlock a guest's inherited open file description.
        // Close only this shell's copy; acquire exclusive on a NEW descriptor.
        "  exec 9>&-",
        "  if [ ! -L \"\$STATE/users.lock\" ]; then",
        "    exec 7>>\"\$STATE/users.lock\"",
        "    if flock -n -x 7 2>/dev/null; then",
        "      if flock -x 8 2>/dev/null; then",
        "      cleanup_one sdcard",
        "      cleanup_one workspace",
        "      cleanup_one root",
        "      cleanup_one dev",
        "      cleanup_one sys",
        "      cleanup_one proc",
        "        flock -u 8",
        "      fi",
        "      flock -u 7",
        "    fi",
        "    exec 7>&-",
        "  fi",
        "  exit \"\$clean_rc\"",
        "}",
        "trap 'cleanup_mounts' EXIT",
        "trap 'exit 129' HUP",
        "trap 'exit 130' INT",
        "trap 'exit 143' TERM"
        ).joinToString("\n")
    }

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
