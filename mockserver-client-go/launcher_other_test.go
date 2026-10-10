//go:build !windows

package mockserver

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLaunchCommand_RunsThePosixLauncherWithoutAShell(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "a b;touch pwned")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(dir, "mockserver")
	script := "#!/bin/sh\necho \"$@\" > \"$(dirname \"$0\")/ran\"\n"
	if err := os.WriteFile(launcher, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}

	cmd, err := launchCommand(launcher, []string{"-serverPort", "1080", "$(id);x"})
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Run(); err != nil {
		t.Fatal(err)
	}

	got, err := os.ReadFile(filepath.Join(dir, "ran"))
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "-serverPort 1080 $(id);x\n" {
		t.Errorf("launcher saw %q", got)
	}
	if _, err := os.Stat(filepath.Join(root, "pwned")); !os.IsNotExist(err) {
		t.Errorf("a shell interpreted the launcher path")
	}
}
