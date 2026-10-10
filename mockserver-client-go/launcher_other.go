//go:build !windows

package mockserver

import "os/exec"

// launchCommand executes the POSIX launcher directly, without a shell.
func launchCommand(launcher string, args []string) (*exec.Cmd, error) {
	return exec.Command(launcher, args...), nil
}
