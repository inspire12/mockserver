//go:build windows

package mockserver

import (
	"os"
	"os/exec"
	"syscall"
)

// launchCommand runs the .bat launcher through cmd.exe. CmdLine is passed to
// CreateProcess unchanged, so os/exec's own quoting (which cmd.exe does not
// follow) is never applied.
func launchCommand(launcher string, args []string) (*exec.Cmd, error) {
	line, err := windowsCommandLine(launcher, args)
	if err != nil {
		return nil, err
	}
	comspec, err := windowsComSpec(os.Getenv)
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(comspec)
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: line}
	return cmd, nil
}
