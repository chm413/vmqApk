// IPC interface for the Shizuku user service.
// The implementation (RemoteShellService) is instantiated by Shizuku in a
// separate process running as shell (uid 2000) or root, so commands sent
// through this interface get system privileges.
package com.vone.vmq;

interface IRemoteShell {
    /**
     * Runs a shell command and returns merged stdout/stderr plus the exit code.
     * Blocking call: must be invoked from a background thread.
     */
    String exec(String command);
}
