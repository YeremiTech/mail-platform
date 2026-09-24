#!/usr/bin/env python3
"""Safe offline tests of backup/restore guards using fake PostgreSQL client executables.
Does not exercise a real database and cannot substitute for a disaster-recovery rehearsal.
"""
import os
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(script, *args, env):
    return subprocess.run(["bash", str(ROOT / "scripts" / script), *map(str, args)],
                          env=env, text=True, capture_output=True)


def test():
    with tempfile.TemporaryDirectory() as tmp:
        base = pathlib.Path(tmp)
        tools = base / "fakebin"
        tools.mkdir()
        # Deliberately tiny fake pg_dump output: only tests naming, checksum, guards and invocation.
        (tools / "pg_dump").write_text("#!/bin/sh\nfor arg in \"$@\"; do case \"$arg\" in --file=*) printf dummy > \"${arg#--file=}\";; esac; done\n")
        (tools / "pg_restore").write_text("#!/bin/sh\nexit 0\n")
        (tools / "psql").write_text("#!/bin/sh\necho RESTORED\n")
        for file in tools.iterdir():
            file.chmod(0o700)
        env = {**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"]}
        backups = base / "backups"
        backup = run("backup-postgres.sh", "mail_platform", backups, env=env)
        assert backup.returncode == 0, backup.stderr
        files = list(backups.glob("*.dump"))
        assert len(files) == 1 and files[0].with_suffix(".dump.sha256").exists()
        content = files[0].with_suffix(".dump.sha256").read_text()
        assert content.strip().endswith(files[0].name), "SHA-256 path is not relative to backup directory"
        target = "isolated_restore"
        refused = run("restore-postgres.sh", files[0], target, env=env)
        assert refused.returncode != 0 and "CONFIRM_RESTORE_DB" in refused.stderr
        authorized = {**env, "CONFIRM_RESTORE_DB": target, "ACKNOWLEDGE_WRITES_STOPPED": "yes"}
        success = run("restore-postgres.sh", files[0], target, env=authorized)
        assert success.returncode == 0, success.stderr
        files[0].write_bytes(b"tampered")
        tampered = run("restore-postgres.sh", files[0], target, env=authorized)
        assert tampered.returncode != 0 and "checksum mismatch" in tampered.stderr
        print("PASS: offline backup checksum, restore authorization and tamper rejection")


if __name__ == "__main__":
    test()
