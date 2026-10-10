using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// The conditional (if/then/else) request matcher and OpenAPI matcher carried on <see cref="HttpRequest"/>,
/// and the per-step <see cref="LoadCheck"/> list on <see cref="LoadStep"/>: wire form, round-trip, and
/// the two website examples (creating_expectations.html button_conditional_request_definition and
/// load_injection.html button_load_step_checks), whose .NET tabs build exactly these objects.
/// </summary>
public class ConditionalMatcherAndLoadCheckTests
{
    private sealed class RecordingHandler : HttpMessageHandler
    {
        public List<(string Path, string? Body)> Requests { get; } = new();
        public Func<string, string> ResponseFor { get; set; } = _ => "{}";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var path = request.RequestUri!.AbsolutePath;
            Requests.Add((path, request.Content != null ? await request.Content.ReadAsStringAsync(cancellationToken) : null));
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            {
                Content = new StringContent(ResponseFor(path), Encoding.UTF8, "application/json")
            };
        }
    }

    // The client's own serializer options (null-omitting, camelCase, HttpRequestConverter).
    private static readonly JsonSerializerOptions ClientOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new HttpRequestConverter() }
    };

    private static (MockServerClient Client, RecordingHandler Handler) CreateClient()
    {
        var handler = new RecordingHandler();
        return (new MockServerClient("http://localhost:1080", new HttpClient(handler)), handler);
    }

    private static void AssertJsonEqual(string actual, string expected)
        => JsonNode.DeepEquals(JsonNode.Parse(actual), JsonNode.Parse(expected))
            .Should().BeTrue($"expected {expected}\nbut got {actual}");

    // -------------------------------------------------------------------
    // Website examples
    // -------------------------------------------------------------------

    [Fact]
    public void WebsiteConditionalExample_SendsTheRestTabJson()
    {
        var (client, handler) = CreateClient();
        handler.ResponseFor = _ => "[]";

        client.When(
            HttpRequest.RequestIf(
                HttpRequest.Request()
                    .WithMethod("POST")
                    .WithHeader("content-type", "application/json"),
                HttpRequest.Request()
                    .WithJsonSchemaBody("{\"type\": \"object\", \"required\": [\"orderId\"]}"),
                HttpRequest.Request()
                    .WithMethod("GET")
            )
        ).Respond(
            HttpResponse.Response()
                .WithStatusCode(200)
        );

        handler.Requests.Should().ContainSingle();
        handler.Requests[0].Path.Should().Be("/mockserver/expectation");
        AssertJsonEqual(handler.Requests[0].Body!, """
            [{
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
            }]
            """);
    }

    [Fact]
    public async Task WebsiteLoadStepChecksExample_SendsTheRestTabJson()
    {
        var (client, handler) = CreateClient();

        var order = HttpRequest.Request().WithMethod("GET").WithPath("/api/orders/123").Build();
        order.SocketAddress = new SocketAddress { Host = "target", Port = 8080 };

        var scenario = new LoadScenario
        {
            Name = "checked-scenario",
            Profile = new LoadProfile
            {
                Stages = new List<LoadStage> { LoadStage.ConstantVus(5, 60000) }
            },
            Thresholds = new List<LoadThreshold>
            {
                new() { Metric = LoadThresholdMetric.CHECK_FAILURE_RATE, Comparator = LoadThresholdComparator.LESS_THAN, Threshold = 0.01 }
            },
            Steps = new List<LoadStep>
            {
                new()
                {
                    Request = order,
                    Checks = new()
                    {
                        new LoadCheck { Source = LoadCheckSource.STATUS, Comparator = LoadCheckComparator.EQUALS, Value = "200" },
                        new LoadCheck { Source = LoadCheckSource.HEADER, HeaderName = "Content-Type", Comparator = LoadCheckComparator.CONTAINS, Value = "application/json" },
                        new LoadCheck { Source = LoadCheckSource.BODY_JSONPATH, JsonPath = "$.status", Comparator = LoadCheckComparator.EQUALS, Value = "CONFIRMED" }
                    }
                }
            }
        };

        await client.RunLoadScenarioAsync(scenario);              // register + start (needs loadGenerationEnabled=true)
        await client.StopLoadScenariosAsync("checked-scenario");  // stop when done

        handler.Requests.Select(r => r.Path).Should().Equal(
            "/mockserver/loadScenario", "/mockserver/loadScenario/start", "/mockserver/loadScenario/stop");
        AssertJsonEqual(handler.Requests[0].Body!, """
            {
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
            }
            """);
        handler.Requests[1].Body.Should().Contain("checked-scenario");
    }

    // -------------------------------------------------------------------
    // Conditional and OpenAPI matchers
    // -------------------------------------------------------------------

    [Fact]
    public void Conditional_WithoutElse_OmitsElse()
    {
        var request = HttpRequest.RequestIf(
            HttpRequest.Request().WithMethod("POST"),
            HttpRequest.Request().WithPath("/orders"));

        AssertJsonEqual(JsonSerializer.Serialize(request, ClientOptions),
            """{"if":{"method":"POST"},"then":{"path":"/orders"}}""");
    }

    [Fact]
    public void Conditional_BuilderAndFactories_WriteNestedConditionalOpenApiAndNot()
    {
        var request = HttpRequest.Request()
            .WithNot(true)
            .WithIf(HttpRequest.OpenApi("https://example.com/petstore.json", "listPets"))
            .WithThen(HttpRequest.RequestIf(
                HttpRequest.Request().WithHeaderMatcher("X-Flag", MatcherValue.Literal("!on")),
                elseRequest: HttpRequest.Request().WithMethod("GET")))
            .WithElse(new HttpRequest { SpecUrlOrPayload = "spec.yaml", ContextPathPrefix = "/api", Not = true })
            .Build();

        AssertJsonEqual(JsonSerializer.Serialize(request, ClientOptions), """
            {
              "not": true,
              "if": { "specUrlOrPayload": "https://example.com/petstore.json", "operationId": "listPets" },
              "then": {
                "if": { "headers": { "X-Flag": [ { "not": false, "value": "!on" } ] } },
                "else": { "method": "GET" }
              },
              "else": { "specUrlOrPayload": "spec.yaml", "contextPathPrefix": "/api", "not": true }
            }
            """);
    }

    [Fact]
    public void Conditional_JsonToTypedToJson_IsIdentical()
    {
        const string json = """
            {
              "httpRequest": {
                "not": true,
                "if": {
                  "if": { "method": "POST", "headers": { "X-Flag": [ { "not": false, "value": "!on" } ] } },
                  "then": { "path": "/orders" }
                },
                "then": {
                  "specUrlOrPayload": { "openapi": "3.0.0", "info": { "title": "t", "version": "1" }, "paths": {} },
                  "operationId": "createOrder"
                },
                "else": { "specUrlOrPayload": "https://example.com/spec.json", "not": true }
              },
              "httpResponse": { "statusCode": 200 }
            }
            """;

        var expectation = JsonSerializer.Deserialize<Expectation>(json, ClientOptions)!;
        var request = expectation.HttpRequest!;

        request.Not.Should().BeTrue();
        request.If!.If!.Method.Should().Be("POST");
        request.If.If.HeaderMatchers!["X-Flag"].Should().ContainSingle();
        request.If.Then!.Path.Should().Be("/orders");
        request.Then!.SpecUrlOrPayload.Should().BeOfType<JsonElement>()
            .Which.GetProperty("openapi").GetString().Should().Be("3.0.0");
        request.Then.OperationId.Should().Be("createOrder");
        request.Else!.SpecUrlOrPayload.Should().Be("https://example.com/spec.json");
        request.Else.Not.Should().BeTrue();

        AssertJsonEqual(JsonSerializer.Serialize(expectation, ClientOptions), json);
    }

    [Fact]
    public void Conditional_ReadFromServerUpsertResponse()
    {
        var (client, handler) = CreateClient();
        handler.ResponseFor = _ =>
            """[{"id":"a1","httpRequest":{"if":{"method":"POST"},"then":{"path":"/p"}},"httpResponse":{"statusCode":200}}]""";

        var created = client.When(HttpRequest.RequestIf(HttpRequest.Request().WithMethod("POST")))
            .Respond(HttpResponse.Response().WithStatusCode(200));

        created.Should().ContainSingle();
        created[0].HttpRequest!.If!.Method.Should().Be("POST");
        created[0].HttpRequest!.Then!.Path.Should().Be("/p");
        created[0].HttpRequest!.Else.Should().BeNull();
    }

    // -------------------------------------------------------------------
    // Load-step checks
    // -------------------------------------------------------------------

    [Fact]
    public void LoadStep_OmitsChecksWhenUnset()
    {
        var step = new LoadStep { Request = HttpRequest.Request().WithMethod("GET").WithPath("/x").Build() };

        AssertJsonEqual(JsonSerializer.Serialize(step, ClientOptions),
            """{"request":{"method":"GET","path":"/x"}}""");
    }

    [Fact]
    public void LoadStep_ChecksRoundTrip()
    {
        const string json = """
            {"request":{"method":"GET","path":"/x"},"checks":[
              {"source":"STATUS","comparator":"GTE","value":"200"},
              {"source":"HEADER","headerName":"Content-Type","comparator":"MATCHES","value":"application/.*"},
              {"source":"BODY_JSONPATH","jsonPath":"$.n","comparator":"LT","value":"10"}]}
            """;

        var step = JsonSerializer.Deserialize<LoadStep>(json, ClientOptions)!;

        step.Checks.Should().HaveCount(3);
        step.Checks![1].HeaderName.Should().Be("Content-Type");
        step.Checks[2].Comparator.Should().Be(LoadCheckComparator.LT);
        AssertJsonEqual(JsonSerializer.Serialize(step, ClientOptions), json);
    }

    [Fact]
    public void GetLoadScenario_ReadsChecksAndIgnoresServerEchoedKeys()
    {
        var (client, handler) = CreateClient();
        handler.ResponseFor = _ =>
            """
            {"name":"checked","state":"LOADED","definition":{"name":"checked",
              "profile":{"stages":[{"type":"VU","vus":5,"durationMillis":60000}]},
              "thresholds":[{"metric":"CHECK_FAILURE_RATE","comparator":"LESS_THAN","threshold":0.01}],
              "steps":[{"request":{"method":"GET","path":"/"},
                "checks":[{"comparator":"GTE","source":"STATUS","valid":true,"value":"200"}]}]}}
            """;

        var entry = client.GetLoadScenario("checked");

        var check = entry.Definition!.Steps![0].Checks!.Single();
        check.Source.Should().Be(LoadCheckSource.STATUS);
        check.Comparator.Should().Be(LoadCheckComparator.GTE);
        check.Value.Should().Be("200");
        entry.Definition.Thresholds![0].Metric.Should().Be(LoadThresholdMetric.CHECK_FAILURE_RATE);
        AssertJsonEqual(JsonSerializer.Serialize(check, ClientOptions),
            """{"source":"STATUS","comparator":"GTE","value":"200"}""");
    }
}
