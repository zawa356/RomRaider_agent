"""Optional real PG process-stop test. Owns only a freshly initialized build/ cluster."""
import importlib.util
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
from urllib.parse import quote


class OwnedPostgres:
    def __init__(self, binaries):
        self.bin = Path(binaries).resolve(strict=True)
        self.suffix = ".exe" if os.name == "nt" else ""
        for name in ("initdb", "pg_ctl", "createdb"):
            if not (self.bin / (name + self.suffix)).is_file():
                raise ValueError("PostgreSQL test binaries missing")
        build = Path("build").resolve(strict=True)
        self.root = Path(tempfile.mkdtemp(prefix="romlogger-http-pg-", dir=build)).resolve()
        if self.root.parent != build or not self.root.name.startswith("romlogger-http-pg-"):
            raise ValueError("invalid owned cluster parent")
        self.data = self.root / "data"
        self.password = secrets.token_urlsafe(24)
        self.started = False
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]

    def command(self, name, *args):
        env = dict(os.environ, PGPASSWORD=self.password)
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        # A Windows postmaster can inherit pipe handles from pg_ctl. Capturing
        # them waits for the long-lived server after pg_ctl has already exited.
        result = subprocess.run([str(self.bin / (name + self.suffix)), *map(str, args)], env=env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=90, creationflags=flags)
        if result.returncode:
            raise RuntimeError("owned PostgreSQL " + name + " failed")

    def initialize(self):
        print("OWNED_POSTGRES initialize isolated cluster", flush=True)
        password_file = self.root / "initialization-password"
        password_file.write_text(self.password + "\n", encoding="utf-8")
        try:
            self.command("initdb", "-D", self.data, "-U", "romlogger_e2e", "--auth-host=scram-sha-256",
                         "--auth-local=trust", "--encoding=UTF8", "--locale=C", "--pwfile=" + str(password_file))
        finally:
            password_file.unlink(missing_ok=True)
        self.start()
        self.command("createdb", "-h", "127.0.0.1", "-p", self.port, "-U", "romlogger_e2e", "romlogger_owned_e2e")

    def start(self):
        print("OWNED_POSTGRES start isolated cluster", flush=True)
        self.command("pg_ctl", "-D", self.data, "-l", self.root / "server.log", "-o",
                     "-h 127.0.0.1 -p " + str(self.port) + " -c shared_buffers=32MB -c max_connections=20", "-w", "start")
        self.started = True

    def stop(self):
        if self.started or (self.data / "postmaster.pid").exists():
            print("OWNED_POSTGRES stop isolated cluster", flush=True)
            # Only this object's new, fixed data path is ever supplied to pg_ctl.
            self.command("pg_ctl", "-D", self.data, "-m", "immediate", "-w", "stop")
            self.started = False


def main():
    if os.environ.get("ROMLOGGER_OWNED_PG_HTTP_ENABLE") != "1":
        raise ValueError("explicit owned-cluster test opt-in required")
    cluster = OwnedPostgres(os.environ["ROMLOGGER_OWNED_PG_BIN"])
    prior = dict(os.environ)
    try:
        cluster.initialize()
        os.environ.update(DATABASE_URL=f"postgresql://romlogger_e2e:{quote(cluster.password)}@127.0.0.1:{cluster.port}/romlogger_owned_e2e",
                          ROMLOGGER_RLC2_PREVIEW_ENABLE="1", ROMLOGGER_RLC2_HTTP_ENABLE="1")
        spec = importlib.util.spec_from_file_location("real_http", Path(__file__).with_name("test-rlc2-real-server-http.py"))
        subject = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(subject)
        subject.main(owned_pg=cluster)
        print("Owned PostgreSQL immediate-stop/restart HTTP: PASS; existing cluster never stopped", flush=True)
    finally:
        cluster.stop()
        os.environ.clear()
        os.environ.update(prior)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("Owned PostgreSQL HTTP FAILED: " + type(exc).__name__, file=sys.stderr)
        raise SystemExit(1)
