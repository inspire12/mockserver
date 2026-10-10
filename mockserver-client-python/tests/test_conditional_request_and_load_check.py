from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from mockserver import (
    Body,
    ConditionalRequestDefinition,
    Expectation,
    HttpRequest,
    HttpResponse,
    KeyToMultiValue,
    LoadCheck,
    LoadProfile,
    LoadScenario,
    LoadStage,
    LoadStep,
    LoadThreshold,
    MockServerClient,
    OpenAPIDefinition,
    SocketAddress,
    Verification,
)


def _wire(model) -> dict:
    return json.loads(json.dumps(model.to_dict()))


def _headers_as_list(matcher: dict) -> dict:
    # The website's REST tab writes headers in the {name: [values]} map form; the
    # client writes the equivalent [{name, values}] list form. Both are accepted.
    headers = matcher.get("headers")
    if isinstance(headers, dict):
        matcher = dict(matcher, headers=[{"name": k, "values": v} for k, v in headers.items()])
    return matcher


# The JSON of the REST API tab of creating_expectations.html#button_conditional_request_definition.
CONDITIONAL_REST_JSON = json.loads(r'''{
    "httpRequest": {
        "if": {
            "method": "POST",
            "headers": { "content-type": ["application/json"] }
        },
        "then": {
            "body": {
                "type": "JSON_SCHEMA",
                "jsonSchema": "{\"type\": \"object\", \"required\": [\"orderId\"]}"
            }
        },
        "else": {
            "method": "GET"
        }
    },
    "httpResponse": {
        "statusCode": 200
    }
}''')

# The JSON of the REST API tab of load_injection.html#button_load_step_checks.
LOAD_CHECKS_REST_JSON = json.loads('''{
    "name": "checked-scenario",
    "profile": { "stages": [ { "type": "VU", "vus": 5, "durationMillis": 60000 } ] },
    "thresholds": [
      { "metric": "CHECK_FAILURE_RATE", "comparator": "LESS_THAN", "threshold": 0.01 }
    ],
    "steps": [
      {
        "request": { "method": "GET", "path": "/api/orders/123",
                     "socketAddress": { "host": "target", "port": 8080 } },
        "checks": [
          { "source": "STATUS", "comparator": "EQUALS", "value": "200" },
          { "source": "HEADER", "headerName": "Content-Type", "comparator": "CONTAINS", "value": "application/json" },
          { "source": "BODY_JSONPATH", "jsonPath": "$.status", "comparator": "EQUALS", "value": "CONFIRMED" }
        ]
      }
    ]
  }''')


class _CapturingHandler(BaseHTTPRequestHandler):
    puts: list[tuple[str, dict]] = []

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length > 0 else b""
        _CapturingHandler.puts.append((self.path, json.loads(raw) if raw else None))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"[]" if self.path == "/mockserver/expectation" else b"{}")

    def log_message(self, *args):
        pass


@pytest.fixture
def capturing_server():
    _CapturingHandler.puts = []
    server = HTTPServer(("127.0.0.1", 0), _CapturingHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server.server_address[1]
    server.shutdown()


class TestWebsiteExamples:
    """Runs each proposed website tab verbatim (only the port differs) with the real
    client and checks what it sends equals the page's REST API tab JSON."""

    def test_conditional_request_definition_tab_matches_rest_json(self, capturing_server):
        port = capturing_server
        # --- creating_expectations.html#button_conditional_request_definition Python tab ---
        client = MockServerClient("localhost", port)
        client.when(
            ConditionalRequestDefinition(
                if_request=HttpRequest(method="POST",
                                       headers=[KeyToMultiValue(name="content-type", values=["application/json"])]),
                then_request=HttpRequest(body=Body.json_schema('{"type": "object", "required": ["orderId"]}')),
                else_request=HttpRequest(method="GET"),
            )
        ).respond(
            HttpResponse(status_code=200)
        )
        # --- end of tab ---
        client.close()
        path, sent = _CapturingHandler.puts[-1]
        assert path == "/mockserver/expectation"
        expected = dict(CONDITIONAL_REST_JSON)
        expected["httpRequest"] = dict(expected["httpRequest"], **{"if": _headers_as_list(expected["httpRequest"]["if"])})
        assert sent == [expected]

    def test_load_step_checks_tab_matches_rest_json(self, capturing_server):
        port = capturing_server
        # --- load_injection.html#button_load_step_checks Python tab ---
        scenario = LoadScenario(
            name="checked-scenario",
            profile=LoadProfile(stages=[
                LoadStage.vu_stage(60000, vus=5),
            ]),
            thresholds=[
                LoadThreshold(metric="CHECK_FAILURE_RATE", comparator="LESS_THAN", threshold=0.01),
            ],
            steps=[
                LoadStep(request=HttpRequest(method="GET", path="/api/orders/123",
                                             socket_address=SocketAddress(host="target", port=8080)),
                         checks=[
                             LoadCheck(source="STATUS", comparator="EQUALS", value="200"),
                             LoadCheck(source="HEADER", header_name="Content-Type",
                                       comparator="CONTAINS", value="application/json"),
                             LoadCheck(source="BODY_JSONPATH", json_path="$.status",
                                       comparator="EQUALS", value="CONFIRMED"),
                         ]),
            ],
        )

        with MockServerClient("localhost", port) as client:
            client.run_load_scenario(scenario)             # register + start (needs loadGenerationEnabled=true)
            client.stop_load_scenarios("checked-scenario")
        # --- end of tab ---
        paths = [p for p, _ in _CapturingHandler.puts]
        assert paths == ["/mockserver/loadScenario", "/mockserver/loadScenario/start", "/mockserver/loadScenario/stop"]
        assert _CapturingHandler.puts[0][1] == LOAD_CHECKS_REST_JSON


class TestConditionalRequestDefinition:
    MATCHER = {
        "if": {"method": "POST", "headers": [{"name": "content-type", "values": ["application/json"]}]},
        "then": {"body": {"type": "JSON_SCHEMA", "jsonSchema": '{"type": "object", "required": ["orderId"]}'}},
        "else": {"method": "GET"},
    }

    def test_typed_serialises_to_if_then_else_inside_http_request(self):
        expectation = Expectation(
            http_request=ConditionalRequestDefinition.request_if(
                HttpRequest(method="POST").with_header("content-type", "application/json"),
                HttpRequest(body=Body.json_schema('{"type": "object", "required": ["orderId"]}')),
                HttpRequest(method="GET"),
            ),
            http_response=HttpResponse(status_code=200),
        )
        assert _wire(expectation) == {"httpRequest": self.MATCHER, "httpResponse": {"statusCode": 200}}

    def test_expectation_reads_http_request_holding_if_as_conditional(self):
        data = {"httpRequest": self.MATCHER, "httpResponse": {"statusCode": 200}}
        expectation = Expectation.from_dict(data)
        condition = expectation.http_request
        assert isinstance(condition, ConditionalRequestDefinition)
        assert [type(b) for b in (condition.if_request, condition.then_request, condition.else_request)] == [HttpRequest] * 3
        assert condition.if_request.method == "POST"
        assert condition.else_request.method == "GET"
        assert _wire(expectation) == data

    def test_nested_conditional_openapi_branch_and_not_round_trip(self):
        nested = {
            "not": True,
            "if": {"path": "/a"},
            "then": {
                "if": {"method": "POST"},
                "then": {
                    "not": True,
                    "specUrlOrPayload": "https://example.com/o.json",
                    "operationId": "createOrder",
                    "contextPathPrefix": "/v1",
                },
                "else": {"specUrlOrPayload": {"openapi": "3.0.0", "paths": {}}},
            },
        }
        model = ConditionalRequestDefinition.from_dict(nested)
        assert model.not_condition is True
        assert isinstance(model.if_request, HttpRequest)
        assert isinstance(model.then_request, ConditionalRequestDefinition)
        assert isinstance(model.then_request.then_request, OpenAPIDefinition)
        assert model.then_request.then_request.context_path_prefix == "/v1"
        assert model.then_request.then_request.not_request is True
        assert isinstance(model.then_request.else_request, OpenAPIDefinition)
        assert model.else_request is None
        assert _wire(model) == nested
        assert _wire(Expectation.from_dict({"httpRequest": nested})) == {"httpRequest": nested}

    def test_absent_else_and_not_are_omitted(self):
        model = ConditionalRequestDefinition(if_request=HttpRequest(method="POST"),
                                             then_request=HttpRequest(path="/orders"))
        assert _wire(model) == {"if": {"method": "POST"}, "then": {"path": "/orders"}}

    def test_from_dict_none(self):
        assert ConditionalRequestDefinition.from_dict(None) is None

    def test_verification_carries_a_conditional_matcher(self):
        verification = Verification(http_request=ConditionalRequestDefinition(
            if_request=HttpRequest(method="POST"), else_request=HttpRequest(method="GET")))
        assert _wire(verification) == {"httpRequest": {"if": {"method": "POST"}, "else": {"method": "GET"}}}

    def test_plain_http_request_still_reads_as_http_request(self):
        data = {"httpRequest": {"path": "/api"}, "httpResponse": {"statusCode": 200}}
        assert isinstance(Expectation.from_dict(data).http_request, HttpRequest)


class TestLoadCheck:
    CHECKS = [
        {"source": "STATUS", "comparator": "EQUALS", "value": "200"},
        {"source": "HEADER", "headerName": "Content-Type", "comparator": "CONTAINS", "value": "application/json"},
        {"source": "BODY_JSONPATH", "jsonPath": "$.status", "comparator": "EQUALS", "value": "CONFIRMED"},
    ]

    def test_step_checks_serialise_and_round_trip(self):
        step = LoadStep(
            request=HttpRequest(method="GET", path="/api/orders/123"),
            checks=[
                LoadCheck(source="STATUS", comparator="EQUALS", value="200"),
                LoadCheck(source="HEADER", header_name="Content-Type", comparator="CONTAINS", value="application/json"),
                LoadCheck(source="BODY_JSONPATH", json_path="$.status", comparator="EQUALS", value="CONFIRMED"),
            ],
        )
        wire = _wire(step)
        assert wire["checks"] == self.CHECKS
        restored = LoadStep.from_dict(wire)
        assert [type(c) for c in restored.checks] == [LoadCheck] * 3
        assert restored.checks[1].header_name == "Content-Type"
        assert restored.checks[2].json_path == "$.status"
        assert _wire(restored) == wire

    def test_checks_omitted_when_unset_or_empty(self):
        assert "checks" not in _wire(LoadStep(request=HttpRequest(path="/")))
        assert "checks" not in _wire(LoadStep(request=HttpRequest(path="/"), checks=[]))

    def test_reads_checks_from_a_server_definition_with_unknown_keys(self):
        definition = {
            "name": "checked",
            "profile": {"stages": [{"type": "VU", "vus": 5, "durationMillis": 60000}]},
            "steps": [{
                "request": {"method": "GET", "path": "/"},
                "checks": [{"comparator": "GTE", "source": "STATUS", "valid": True, "value": "200"}],
            }],
        }
        check = LoadScenario.from_dict(definition).steps[0].checks[0]
        assert isinstance(check, LoadCheck)
        assert check.to_dict() == {"source": "STATUS", "comparator": "GTE", "value": "200"}
