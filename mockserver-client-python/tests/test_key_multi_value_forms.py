"""Headers, query and path parameters, trailers and cookies given in the dict
forms the server's JSON accepts, as the website's Python examples write them."""

from __future__ import annotations

import pytest

from mockserver.models import Expectation, HttpRequest, HttpResponse, KeyToMultiValue


class TestHttpResponseDictForms:
    def test_headers_as_dict_of_lists(self):
        response = HttpResponse(status_code=302, headers={"Location": ["https://www.mock-server.com"]})
        assert response.to_dict() == {
            "statusCode": 302,
            "headers": [{"name": "Location", "values": ["https://www.mock-server.com"]}],
        }

    def test_headers_as_dict_of_single_values(self):
        response = HttpResponse(headers={"Content-Type": "application/json", "X-Many": ["a", "b"]})
        assert response.to_dict()["headers"] == [
            {"name": "Content-Type", "values": ["application/json"]},
            {"name": "X-Many", "values": ["a", "b"]},
        ]

    def test_cookies_and_trailers_as_dicts(self):
        response = HttpResponse(cookies={"session": "abc"}, trailers={"grpc-status": "0"})
        assert response.to_dict() == {
            "cookies": {"session": "abc"},
            "trailers": [{"name": "grpc-status", "values": ["0"]}],
        }

    def test_list_of_dicts(self):
        response = HttpResponse(
            headers=[{"name": "A", "values": ["1"]}],
            cookies=[{"name": "c", "value": "v"}],
        )
        assert response.headers == [KeyToMultiValue(name="A", values=["1"])]
        assert response.to_dict()["cookies"] == {"c": "v"}

    def test_dict_form_round_trips(self):
        response = HttpResponse(headers={"A": ["1"]}, cookies={"c": "v"}, trailers={"T": ["x"]})
        assert HttpResponse.from_dict(response.to_dict()) == response

    def test_dict_assigned_after_construction(self):
        response = HttpResponse()
        response.headers = {"A": "1"}
        response.cookies = {"c": "v"}
        assert response.to_dict() == {"headers": [{"name": "A", "values": ["1"]}], "cookies": {"c": "v"}}

    def test_with_header_after_dict_assignment(self):
        response = HttpResponse()
        response.headers = {"A": "1"}
        response.with_header("B", "2")
        assert response.to_dict()["headers"] == [
            {"name": "A", "values": ["1"]},
            {"name": "B", "values": ["2"]},
        ]

    def test_rejects_a_string(self):
        with pytest.raises(TypeError):
            HttpResponse(headers="Content-Type: text/plain")


class TestHttpRequestDictForms:
    def test_every_collection_as_a_dict(self):
        request = HttpRequest(
            path="/x",
            headers={"Accept": "application/json"},
            query_string_parameters={"q": ["1", "2"]},
            path_parameters={"id": "7"},
            cookies={"session": "abc"},
        )
        assert request.to_dict() == {
            "path": "/x",
            "headers": [{"name": "Accept", "values": ["application/json"]}],
            "queryStringParameters": [{"name": "q", "values": ["1", "2"]}],
            "pathParameters": {"id": ["7"]},
            "cookies": {"session": "abc"},
        }

    def test_list_of_key_to_multi_value_still_works(self):
        headers = [KeyToMultiValue(name="A", values=["1"])]
        request = HttpRequest(headers=headers)
        assert request.headers == headers
        assert request.to_dict() == {"headers": [{"name": "A", "values": ["1"]}]}

    def test_a_list_given_is_kept_and_appended_to(self):
        headers = [KeyToMultiValue(name="A", values=["1"])]
        cookies = [KeyToMultiValue(name="c", values=["v"])]
        request = HttpRequest(headers=headers, cookies=cookies).with_header("B", "2").with_cookie("d", "w")
        response = HttpResponse(headers=headers).with_header("C", "3")
        request.to_dict()
        response.to_dict()
        assert request.headers is headers and response.headers is headers and request.cookies is cookies
        assert [h.name for h in headers] == ["A", "B", "C"]
        assert [c.name for c in cookies] == ["c", "d"]

    def test_an_empty_list_given_is_kept_and_appended_to(self):
        trailers: list[KeyToMultiValue] = []
        HttpResponse(trailers=trailers).with_trailer("t", "v")
        assert trailers == [KeyToMultiValue(name="t", values=["v"])]

    def test_dict_form_round_trips(self):
        request = HttpRequest(headers={"A": ["1"]}, query_string_parameters={"q": "v"}, cookies={"c": "v"})
        assert HttpRequest.from_dict(request.to_dict()) == request

    def test_with_query_param_after_dict_assignment(self):
        request = HttpRequest()
        request.query_string_parameters = {"a": "1"}
        request.with_query_param("b", "2")
        assert request.to_dict()["queryStringParameters"] == [
            {"name": "a", "values": ["1"]},
            {"name": "b", "values": ["2"]},
        ]

    def test_cookies_dict_assigned_after_construction(self):
        request = HttpRequest()
        request.cookies = {"c": "v"}
        request.with_cookie("d", "w")
        assert request.to_dict() == {"cookies": {"c": "v", "d": "w"}}

    def test_expectation_with_dict_headers(self):
        expectation = Expectation(
            http_request=HttpRequest(method="POST", path="/login", headers={"Content-Type": "application/json"}),
            http_response=HttpResponse(status_code=302, headers={"Location": ["https://www.mock-server.com"]}),
        )
        wire = expectation.to_dict()
        assert wire["httpRequest"]["headers"] == [{"name": "Content-Type", "values": ["application/json"]}]
        assert wire["httpResponse"]["headers"] == [{"name": "Location", "values": ["https://www.mock-server.com"]}]


class TestKeyMatchStyle:
    @pytest.mark.parametrize("wire_key", ["headers", "queryStringParameters", "pathParameters"])
    def test_object_form_with_key_match_style_round_trips(self, wire_key):
        wire = {wire_key: {"keyMatchStyle": "MATCHING_KEY", "a": ["1", "2"]}}
        request = HttpRequest.from_dict(wire)
        assert all(item.name != "keyMatchStyle" for item in getattr(request, _attribute(wire_key)))
        assert request.to_dict() == wire

    def test_key_match_style_in_a_constructor_dict(self):
        request = HttpRequest(headers={"keyMatchStyle": "MATCHING_KEY", "Accept": "text/plain"})
        assert request.headers_key_match_style == "MATCHING_KEY"
        assert request.headers == [KeyToMultiValue(name="Accept", values=["text/plain"])]
        assert request.to_dict() == {"headers": {"keyMatchStyle": "MATCHING_KEY", "Accept": ["text/plain"]}}

    def test_key_match_style_in_an_assigned_dict(self):
        request = HttpRequest()
        request.query_string_parameters = {"keyMatchStyle": "MATCHING_KEY", "q": "1"}
        assert request.to_dict() == {"queryStringParameters": {"keyMatchStyle": "MATCHING_KEY", "q": ["1"]}}

    def test_key_match_style_in_an_assigned_dict_survives_with_query_param(self):
        request = HttpRequest()
        request.query_string_parameters = {"keyMatchStyle": "MATCHING_KEY", "a": "1"}
        request.with_query_param("b", "2")
        assert request.to_dict() == {
            "queryStringParameters": {"keyMatchStyle": "MATCHING_KEY", "a": ["1"], "b": ["2"]}
        }

    def test_key_match_style_set_explicitly(self):
        request = HttpRequest(
            query_string_parameters=[KeyToMultiValue(name="q", values=["1"])],
            query_string_parameters_key_match_style="MATCHING_KEY",
        ).with_query_param("q", "2")
        assert request.to_dict() == {"queryStringParameters": {"keyMatchStyle": "MATCHING_KEY", "q": ["1", "2"]}}

    def test_a_non_string_name_with_key_match_style_names_the_field(self):
        request = HttpRequest(
            headers=[KeyToMultiValue(name={"not": True, "value": "X-Tag"}, values=["a"])],  # type: ignore[arg-type]
            headers_key_match_style="MATCHING_KEY",
        )
        with pytest.raises(ValueError, match="^headers with keyMatchStyle"):
            request.to_dict()

    def test_without_key_match_style_headers_keep_the_array_form(self):
        assert HttpRequest.from_dict({"headers": {"a": ["1"]}}).to_dict() == {
            "headers": [{"name": "a", "values": ["1"]}]
        }


def _attribute(wire_key: str) -> str:
    return {
        "headers": "headers",
        "queryStringParameters": "query_string_parameters",
        "pathParameters": "path_parameters",
    }[wire_key]
