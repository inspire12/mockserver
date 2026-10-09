"""HttpOverrideForwardedRequest: the requestOverride / responseOverride fields and
their older httpRequest / httpResponse aliases.

The server reads either pair, but only the alias pair ALONE (with delay and
primary): its schema rejects httpRequest or httpResponse next to a modifier or
responseTemplate. It always returns the canonical names.
"""

from __future__ import annotations

import json

import pytest

from mockserver.client import MockServerClient
from mockserver.models import (
    Delay,
    Expectation,
    ExpectationStep,
    HttpOverrideForwardedRequest,
    HttpRequest,
    HttpResponse,
    HttpTemplate,
)

from tests.test_client import SyncMockHandler, sync_mock_server  # noqa: F401  (fixture)

# What MockServer returns from PUT /mockserver/retrieve?type=ACTIVE_EXPECTATIONS
# for an expectation created with every override field (keys as the server orders them).
SERVER_RETRIEVED_OVERRIDE = {
    "delay": {"timeUnit": "SECONDS", "value": 1},
    "primary": True,
    "requestModifier": {"path": {"regex": "^/(.+)$", "substitution": "/prefix/$1"}},
    "requestOverride": {"path": "/other", "headers": {"Host": ["target.host.com"]}},
    "responseModifier": {"headers": {"remove": ["Server"]}},
    "responseOverride": {"statusCode": 202, "body": "overridden"},
    "responseTemplate": {"template": '{ "statusCode": 200 }', "templateType": "VELOCITY"},
}

# The same override as the client writes it: headers in the array form, which the
# server reads identically.
CLIENT_WRITTEN_OVERRIDE = {
    **SERVER_RETRIEVED_OVERRIDE,
    "requestOverride": {"path": "/other", "headers": [{"name": "Host", "values": ["target.host.com"]}]},
}

SERVER_RETRIEVED_EXPECTATION = {
    "httpOverrideForwardedRequest": SERVER_RETRIEVED_OVERRIDE,
    "httpRequest": {"path": "/full"},
    "id": "f857786d-4ea4-463b-9778-6d4ea4463bc1",
    "priority": 0,
    "timeToLive": {"unlimited": True},
    "times": {"unlimited": True},
}


def _wire(model) -> dict:
    return json.loads(json.dumps(model.to_dict()))


class TestCanonicalFields:
    def test_request_override_and_response_override_are_constructor_arguments(self):
        o = HttpOverrideForwardedRequest(
            request_override=HttpRequest(path="/other"),
            response_override=HttpResponse(status_code=202),
        )
        assert o.request_override.path == "/other"
        assert o.response_override.status_code == 202

    def test_serialised_as_request_override_and_response_override(self):
        o = HttpOverrideForwardedRequest(
            request_override=HttpRequest(path="/other"),
            response_override=HttpResponse(status_code=202),
        )
        assert _wire(o) == {
            "requestOverride": {"path": "/other"},
            "responseOverride": {"statusCode": 202},
        }

    def test_every_field_serialised(self):
        o = HttpOverrideForwardedRequest(
            request_override=HttpRequest(path="/other", headers={"Host": ["target.host.com"]}),
            request_modifier={"path": {"regex": "^/(.+)$", "substitution": "/prefix/$1"}},
            response_override=HttpResponse(status_code=202, body="overridden"),
            response_modifier={"headers": {"remove": ["Server"]}},
            response_template=HttpTemplate(template_type="VELOCITY", template='{ "statusCode": 200 }'),
            delay=Delay(time_unit="SECONDS", value=1),
            primary=True,
        )
        assert _wire(o) == CLIENT_WRITTEN_OVERRIDE

    def test_from_dict_reads_request_override_and_response_override(self):
        o = HttpOverrideForwardedRequest.from_dict(SERVER_RETRIEVED_OVERRIDE)
        assert o.request_override.path == "/other"
        assert o.response_override.status_code == 202
        assert o.response_override.body == "overridden"
        assert o.response_template.template_type == "VELOCITY"
        assert o.request_modifier["path"]["substitution"] == "/prefix/$1"
        assert o.response_modifier == {"headers": {"remove": ["Server"]}}
        assert o.delay.value == 1
        assert o.primary is True

    def test_server_form_round_trips(self):
        o = HttpOverrideForwardedRequest.from_dict(SERVER_RETRIEVED_OVERRIDE)
        assert _wire(o) == CLIENT_WRITTEN_OVERRIDE


class TestAliases:
    def test_from_dict_reads_the_aliases(self):
        o = HttpOverrideForwardedRequest.from_dict({
            "httpRequest": {"path": "/fwd"},
            "httpResponse": {"statusCode": 201},
        })
        assert o.http_request.path == "/fwd"
        assert o.http_response.status_code == 201

    def test_aliases_alone_keep_their_wire_names(self):
        alias_form = {
            "httpRequest": {"path": "/fwd"},
            "httpResponse": {"body": "overridden"},
            "delay": {"timeUnit": "MILLISECONDS", "value": 5},
            "primary": True,
        }
        assert _wire(HttpOverrideForwardedRequest.from_dict(alias_form)) == alias_form

    @pytest.mark.parametrize("extra, key", [
        ({"request_modifier": {"path": {"regex": "x"}}}, "requestModifier"),
        ({"response_modifier": {"headers": {"remove": ["Server"]}}}, "responseModifier"),
        ({"response_template": HttpTemplate(template_type="VELOCITY", template="{}")}, "responseTemplate"),
    ])
    def test_aliases_with_a_canonical_only_field_are_written_under_the_canonical_names(self, extra, key):
        o = HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/fwd"),
            http_response=HttpResponse(status_code=201),
            **extra,
        )
        wire = _wire(o)
        assert wire["requestOverride"] == {"path": "/fwd"}
        assert wire["responseOverride"] == {"statusCode": 201}
        assert "httpRequest" not in wire
        assert "httpResponse" not in wire
        assert key in wire

    def test_alias_next_to_a_canonical_override_is_written_under_the_canonical_name(self):
        o = HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/fwd"),
            response_override=HttpResponse(status_code=201),
        )
        assert _wire(o) == {
            "requestOverride": {"path": "/fwd"},
            "responseOverride": {"statusCode": 201},
        }

    def test_request_override_and_its_alias_together_are_rejected(self):
        o = HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/a"),
            request_override=HttpRequest(path="/b"),
        )
        with pytest.raises(ValueError, match="request_override"):
            o.to_dict()

    def test_response_override_and_its_alias_together_are_rejected(self):
        o = HttpOverrideForwardedRequest(
            http_response=HttpResponse(status_code=200),
            response_override=HttpResponse(status_code=201),
        )
        with pytest.raises(ValueError, match="response_override"):
            o.to_dict()

    def test_existing_positional_order_is_unchanged(self):
        o = HttpOverrideForwardedRequest(HttpRequest(path="/a"), HttpResponse(status_code=201))
        assert o.http_request.path == "/a"
        assert o.http_response.status_code == 201


class TestRetrievedExpectations:
    def test_expectation_from_the_server_round_trips(self):
        e = Expectation.from_dict(SERVER_RETRIEVED_EXPECTATION)
        assert e.http_override_forwarded_request.request_override.path == "/other"
        assert _wire(e)["httpOverrideForwardedRequest"] == CLIENT_WRITTEN_OVERRIDE

    def test_step_from_the_server_round_trips(self):
        step = ExpectationStep.from_dict({"httpOverrideForwardedRequest": SERVER_RETRIEVED_OVERRIDE, "responder": True})
        assert _wire(step) == {"httpOverrideForwardedRequest": CLIENT_WRITTEN_OVERRIDE, "responder": True}

    def test_retrieve_active_expectations_keeps_the_overrides(self, sync_mock_server):  # noqa: F811
        SyncMockHandler.response_body = json.dumps([SERVER_RETRIEVED_EXPECTATION])
        with MockServerClient("127.0.0.1", sync_mock_server) as client:
            [retrieved] = client.retrieve_active_expectations()
        override = retrieved.http_override_forwarded_request
        assert override.request_override.headers is not None
        assert override.response_override.body == "overridden"
        assert _wire(override) == CLIENT_WRITTEN_OVERRIDE
