from __future__ import annotations

from mockserver.models import Expectation, OpenAPIDefinition, Verification

SPEC = "https://example.com/petstore.json"


def test_expectation_from_dict_keeps_an_openapi_request_matcher():
    expectation = Expectation.from_dict({
        "httpRequest": {"specUrlOrPayload": SPEC, "operationId": "showPetById"},
        "httpResponse": {"body": "some_response_body"},
    })
    assert isinstance(expectation.http_request, OpenAPIDefinition)
    assert expectation.to_dict()["httpRequest"] == {"specUrlOrPayload": SPEC, "operationId": "showPetById"}


def test_verification_from_dict_keeps_an_openapi_request_matcher():
    verification = Verification.from_dict({
        "httpRequest": {"specUrlOrPayload": SPEC, "operationId": "listPets"},
        "times": {"atLeast": 2},
    })
    assert verification.to_dict()["httpRequest"] == {"specUrlOrPayload": SPEC, "operationId": "listPets"}


def test_expectation_from_dict_still_reads_a_plain_request():
    expectation = Expectation.from_dict({"httpRequest": {"path": "/some/path"}})
    assert expectation.to_dict()["httpRequest"] == {"path": "/some/path"}

