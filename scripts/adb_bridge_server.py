#!/usr/bin/env python3
import argparse
import contextlib
import json
import os
import re
import shlex
import socket
import socketserver
import subprocess
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent.parent
ADB_BIN = Path(
    os.environ.get(
        "ADB_BRIDGE_ADB_BIN",
        str(ROOT_DIR / "build" / "outputs" / "host-tools" / "adb" / "adb"),
    )
).resolve()
SCRCPY_SERVER_BIN = Path(
    os.environ.get(
        "ADB_BRIDGE_SCRCPY_SERVER_BIN",
        str(ROOT_DIR / "build" / "outputs" / "host-tools" / "scrcpy" / "scrcpy-server"),
    )
).resolve()
SCRCPY_SERVER_DEST = "/data/local/tmp/scrcpy-server.jar"
SCRCPY_VERSION_FILE = ROOT_DIR / "scrcpy" / "server" / "build.gradle"

CODEC_ID_H264 = 0x68323634


def normalize_target(host: str, port: int) -> str:
    return f"{host}:{port}"


def read_scrcpy_version() -> str:
    content = SCRCPY_VERSION_FILE.read_text(encoding="utf-8")
    match = re.search(r'versionName\s+"([^"]+)"', content)
    if not match:
        raise RuntimeError(f"Unable to read scrcpy version from {SCRCPY_VERSION_FILE}")
    return match.group(1)


SCRCPY_VERSION = read_scrcpy_version()


def allocate_tcp_port() -> int:
    with contextlib.closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def run_adb(*args: str) -> tuple[int, str]:
    completed = subprocess.run(
        [str(ADB_BIN), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    return completed.returncode, completed.stdout.strip()


def ensure_runtime_tools() -> None:
    if not ADB_BIN.is_file():
        raise FileNotFoundError(f"Missing staged adb binary at {ADB_BIN}")
    if not SCRCPY_SERVER_BIN.is_file():
        raise FileNotFoundError(f"Missing staged scrcpy-server at {SCRCPY_SERVER_BIN}")


class TcpProxyServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, bind_port: int, upstream_port: int):
        self.upstream_port = upstream_port
        super().__init__(("0.0.0.0", bind_port), TcpProxyHandler)


class TcpProxyHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        try:
            with socket.create_connection(("127.0.0.1", self.server.upstream_port), timeout=10) as upstream:
                upstream.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

                downstream = threading.Thread(target=self._pipe, args=(self.request, upstream), daemon=True)
                upstream_thread = threading.Thread(target=self._pipe, args=(upstream, self.request), daemon=True)
                downstream.start()
                upstream_thread.start()
                downstream.join()
                upstream_thread.join()
        except OSError:
            return

    @staticmethod
    def _pipe(source: socket.socket, target: socket.socket) -> None:
        try:
            while True:
                data = source.recv(65536)
                if not data:
                    break
                target.sendall(data)
        except OSError:
            return
        finally:
            with contextlib.suppress(OSError):
                target.shutdown(socket.SHUT_WR)


class ScrcpySession:
    def __init__(self, target: str):
        self.target = target
        self.adb_local_port = allocate_tcp_port()
        self.stream_port = allocate_tcp_port()
        self.control_port = allocate_tcp_port()
        self.process: subprocess.Popen[str] | None = None
        self.video_proxy: TcpProxyServer | None = None
        self.video_proxy_thread: threading.Thread | None = None
        self.control_proxy: TcpProxyServer | None = None
        self.control_proxy_thread: threading.Thread | None = None

    def start(self) -> None:
        ensure_runtime_tools()

        return_code, output = run_adb("connect", self.target)
        lowered = output.lower()
        if return_code != 0 or ("connected to" not in lowered and "already connected to" not in lowered):
            raise RuntimeError(output or f"adb connect failed for {self.target}")

        return_code, output = run_adb("-s", self.target, "wait-for-device")
        if return_code != 0:
            raise RuntimeError(output or f"adb wait-for-device failed for {self.target}")

        return_code, output = run_adb("-s", self.target, "push", str(SCRCPY_SERVER_BIN), SCRCPY_SERVER_DEST)
        if return_code != 0:
            raise RuntimeError(output or "adb push scrcpy-server failed")

        run_adb("-s", self.target, "forward", "--remove", f"tcp:{self.adb_local_port}")
        return_code, output = run_adb(
            "-s", self.target, "forward", f"tcp:{self.adb_local_port}", "localabstract:scrcpy"
        )
        if return_code != 0:
            raise RuntimeError(output or "adb forward failed")

        self.video_proxy = TcpProxyServer(self.stream_port, self.adb_local_port)
        self.video_proxy_thread = threading.Thread(
            target=self.video_proxy.serve_forever, name="scrcpy-video-proxy", daemon=True
        )
        self.video_proxy_thread.start()

        self.control_proxy = TcpProxyServer(self.control_port, self.adb_local_port)
        self.control_proxy_thread = threading.Thread(
            target=self.control_proxy.serve_forever, name="scrcpy-control-proxy", daemon=True
        )
        self.control_proxy_thread.start()

        scid = f"{int(time.time() * 1000) & 0x7FFFFFFF:08x}"
        server_args = [
            SCRCPY_VERSION,
            f"scid={scid}",
            "log_level=info",
            "video=true",
            "audio=false",
            "control=true",
            "tunnel_forward=true",
            "cleanup=true",
            "send_device_meta=false",
            "send_dummy_byte=false",
            "send_codec_meta=true",
            "send_frame_meta=true",
            f"video_codec=h264",
        ]
        remote_cmd = (
            f"CLASSPATH={SCRCPY_SERVER_DEST} app_process / com.genymobile.scrcpy.Server "
            + " ".join(shlex.quote(arg) for arg in server_args)
        )
        self.process = subprocess.Popen(
            [str(ADB_BIN), "-s", self.target, "shell", remote_cmd],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )

        time.sleep(0.35)
        if self.process.poll() is not None:
            output = self.process.stdout.read().strip() if self.process.stdout else ""
            raise RuntimeError(output or "scrcpy-server terminated immediately")

    def stop(self) -> None:
        if self.video_proxy is not None:
            self.video_proxy.shutdown()
            self.video_proxy.server_close()
            self.video_proxy = None
        if self.video_proxy_thread is not None:
            self.video_proxy_thread.join(timeout=1)
            self.video_proxy_thread = None

        if self.control_proxy is not None:
            self.control_proxy.shutdown()
            self.control_proxy.server_close()
            self.control_proxy = None
        if self.control_proxy_thread is not None:
            self.control_proxy_thread.join(timeout=1)
            self.control_proxy_thread = None

        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        self.process = None

        if ADB_BIN.is_file():
            run_adb("-s", self.target, "forward", "--remove", f"tcp:{self.adb_local_port}")

    def describe(self) -> dict:
        return {
            "target": self.target,
            "streamPort": self.stream_port,
            "controlPort": self.control_port,
            "videoCodecId": CODEC_ID_H264,
            "videoCodec": "h264",
        }


class SessionManager:
    def __init__(self):
        self._lock = threading.Lock()
        self._session: ScrcpySession | None = None

    def start(self, target: str) -> dict:
        with self._lock:
            self._replace_locked(None)
            session = ScrcpySession(target)
            try:
                session.start()
            except Exception:
                session.stop()
                raise
            self._session = session
            return session.describe()

    def stop(self) -> None:
        with self._lock:
            self._replace_locked(None)

    def status(self) -> dict:
        with self._lock:
            if self._session is None:
                return {"active": False}
            return {"active": True, **self._session.describe()}

    def _replace_locked(self, session: ScrcpySession | None) -> None:
        if self._session is not None:
            self._session.stop()
        self._session = session


SESSION_MANAGER = SessionManager()


class AdbBridgeHandler(BaseHTTPRequestHandler):
    server_version = "AdbBridge/2.0"

    def do_POST(self) -> None:
        if self.path == "/api/adb/connect":
            self._handle_adb_connect()
            return
        if self.path == "/api/scrcpy/session/start":
            self._handle_scrcpy_session_start()
            return
        if self.path == "/api/scrcpy/session/stop":
            self._handle_scrcpy_session_stop()
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def do_GET(self) -> None:
        if self.path == "/healthz":
            self._write_json(
                HTTPStatus.OK,
                {
                    "ok": True,
                    "adbPath": str(ADB_BIN),
                    "adbExists": ADB_BIN.is_file(),
                    "scrcpyServerExists": SCRCPY_SERVER_BIN.is_file(),
                    "session": SESSION_MANAGER.status(),
                },
            )
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def log_message(self, format: str, *args) -> None:
        return

    def _read_json_body(self) -> dict:
        content_length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(content_length)
        return json.loads(payload.decode("utf-8")) if payload else {}

    def _handle_adb_connect(self) -> None:
        try:
            ensure_runtime_tools()
            data = self._read_json_body()
            host = str(data["host"]).strip()
            port = int(data["port"])
            if not host or not (1 <= port <= 65535):
                raise ValueError("Invalid host or port")
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            self._write_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": f"Invalid request: {exc}"})
            return
        except FileNotFoundError as exc:
            self._write_json(HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "message": str(exc)})
            return

        target = normalize_target(host, port)
        return_code, output = run_adb("connect", target)
        lowered = output.lower()
        ok = return_code == 0 and ("connected to" in lowered or "already connected to" in lowered)
        status = HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY
        self._write_json(
            status,
            {
                "ok": ok,
                "target": target,
                "returnCode": return_code,
                "message": output or "adb connect returned no output",
            },
        )

    def _handle_scrcpy_session_start(self) -> None:
        try:
            ensure_runtime_tools()
            data = self._read_json_body()
            host = str(data["host"]).strip()
            port = int(data["port"])
            if not host or not (1 <= port <= 65535):
                raise ValueError("Invalid host or port")
            target = normalize_target(host, port)
            session_payload = SESSION_MANAGER.start(target)
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            self._write_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": f"Invalid request: {exc}"})
            return
        except FileNotFoundError as exc:
            self._write_json(HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "message": str(exc)})
            return
        except RuntimeError as exc:
            self._write_json(HTTPStatus.BAD_GATEWAY, {"ok": False, "message": str(exc)})
            return

        self._write_json(
            HTTPStatus.OK,
            {
                "ok": True,
                "message": f"scrcpy video session ready for {target}",
                **session_payload,
            },
        )

    def _handle_scrcpy_session_stop(self) -> None:
        SESSION_MANAGER.stop()
        self._write_json(HTTPStatus.OK, {"ok": True, "message": "scrcpy video session stopped"})

    def _write_json(self, status: HTTPStatus, payload: dict) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main() -> None:
    parser = argparse.ArgumentParser(description="ADB/scrcpy bridge for the Android app")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), AdbBridgeHandler)
    print(f"ADB bridge listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        SESSION_MANAGER.stop()
        server.server_close()


if __name__ == "__main__":
    main()
