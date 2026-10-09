from __future__ import annotations

import inspect
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from mockserver.client import MockServerClient, SyncForwardChainExpectation
from mockserver.fluent import ForwardChainExpectation
from mockserver.models import (
    ExpectationStep,
    GrpcBidiResponse,
    HttpChaosProfile,
    HttpRequest,
    HttpResponse,
)


class _Recorder(BaseHTTPRequestHandler):
    bodies: list = []

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", 0))
        _Recorder.bodies.append(json.loads(self.rfile.read(length)))
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"[]")

    def log_message(self, format, *args):
        pass


@pytest.fixture
def port():
    server = HTTPServer(("127.0.0.1", 0), _Recorder)
    _Recorder.bodies = []
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield server.server_address[1]
    server.shutdown()


def test_sync_chain_offers_every_method_of_the_async_chain():
    async_methods = {
        name for name, _ in inspect.getmembers(ForwardChainExpectation, inspect.isfunction)
        if not name.startswith("_")
    }
    sync_methods = {
        name for name, _ in inspect.getmembers(SyncForwardChainExpectation, inspect.isfunction)
        if not name.startswith("_")
    }
    assert async_methods - sync_methods == set()


def test_with_chaos_sends_the_chaos_profile(port):
    with MockServerClient("127.0.0.1", port) as client:
        client.when(HttpRequest(path="/flaky")).with_chaos(
            HttpChaosProfile(error_status=503, error_probability=0.5)
        ).respond(HttpResponse(status_code=200))
    chaos = _Recorder.bodies[-1][0]["chaos"]
    assert chaos["errorStatus"] == 503
    assert chaos["errorProbability"] == 0.5


def test_respond_with_grpc_bidi_sends_the_bidi_response(port):
    with MockServerClient("127.0.0.1", port) as client:
        client.when(HttpRequest(path="/chat.Chat/Talk")).respond_with_grpc_bidi(
            GrpcBidiResponse(status_name="OK")
        )
    assert _Recorder.bodies[-1][0]["grpcBidiResponse"]["statusName"] == "OK"


def test_with_steps_sends_the_steps(port):
    with MockServerClient("127.0.0.1", port) as client:
        client.when(HttpRequest(path="/order")).with_steps([
            ExpectationStep(http_response=HttpResponse(status_code=202), responder=True),
        ])
    steps = _Recorder.bodies[-1][0]["steps"]
    assert steps[0]["responder"] is True
    assert steps[0]["httpResponse"]["statusCode"] == 202
