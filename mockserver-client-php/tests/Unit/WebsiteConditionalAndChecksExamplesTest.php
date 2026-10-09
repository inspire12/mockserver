<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\LoadCheck;
use MockServer\LoadProfile;
use MockServer\LoadScenario;
use MockServer\LoadStage;
use MockServer\LoadThreshold;
use MockServer\MockServerClient;
use MockServer\Tests\Support\JsonCanon;
use PHPUnit\Framework\TestCase;

/**
 * Runs the PHP tab code of two website examples against a mocked transport and
 * asserts the JSON it sends equals the example's REST API tab:
 * creating_expectations.html#button_conditional_request_definition and
 * load_injection.html#button_load_step_checks.
 */
class WebsiteConditionalAndChecksExamplesTest extends TestCase
{
    /**
     * @param array<Response> $responses
     * @param array<array> &$history
     */
    private function createClientWithMock(array $responses, array &$history = []): MockServerClient
    {
        $mock = new MockHandler($responses);
        $handlerStack = HandlerStack::create($mock);
        $handlerStack->push(Middleware::history($history));

        $client = new MockServerClient('localhost', 1080);

        $reflection = new \ReflectionClass($client);
        $prop = $reflection->getProperty('httpClient');
        $prop->setValue($client, new GuzzleClient([
            'handler' => $handlerStack,
            'http_errors' => false,
            'headers' => ['Content-Type' => 'application/json; charset=utf-8'],
        ]));

        return $client;
    }

    public function testConditionalRequestDefinitionExampleSendsTheRestTabJson(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(201, [], '')], $history);

        // --- PHP tab code ---
        $client->when(
            HttpRequest::conditional(
                HttpRequest::request()->method('POST')->header('content-type', 'application/json'),
                HttpRequest::request()->jsonSchemaBody('{"type": "object", "required": ["orderId"]}'),
                HttpRequest::request()->method('GET')
            )
        )->respond(
            HttpResponse::response()->statusCode(200)
        );
        // --- end of PHP tab code ---

        $restTab = <<<'JSON'
{
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
}
JSON;

        $this->assertCount(1, $history);
        $this->assertSame('/mockserver/expectation', $history[0]['request']->getUri()->getPath());
        $this->assertSame(
            [JsonCanon::decode($restTab)],
            JsonCanon::decode((string) $history[0]['request']->getBody()),
        );
    }

    public function testLoadStepChecksExampleSendsTheRestTabJson(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
            new Response(200, [], '{}'),
            new Response(200, [], '{}'),
        ], $history);

        // --- PHP tab code (after the client is built) ---
        $scenario = LoadScenario::scenario('checked-scenario')
            ->profile(LoadProfile::of(
                LoadStage::vuHold(5, 60000),
            ))
            ->thresholds(
                LoadThreshold::of('CHECK_FAILURE_RATE', 'LESS_THAN', 0.01),
            )
            ->addStep(
                HttpRequest::request()->method('GET')->path('/api/orders/123')
                    ->socketAddress('target', 8080),
                checks: [
                    LoadCheck::status('EQUALS', '200'),
                    LoadCheck::header('Content-Type', 'CONTAINS', 'application/json'),
                    LoadCheck::bodyJsonPath('$.status', 'EQUALS', 'CONFIRMED'),
                ],
            );

        $client->runLoadScenario($scenario);             // register + start (needs loadGenerationEnabled=true)
        $client->stopLoadScenarios('checked-scenario');
        // --- end of PHP tab code ---

        // The REST API tab's registration body, except that the PHP client's
        // socketAddress() always writes a scheme (HTTP, the server's default).
        $restTab = <<<'JSON'
{
    "name": "checked-scenario",
    "profile": { "stages": [ { "type": "VU", "vus": 5, "durationMillis": 60000 } ] },
    "thresholds": [
      { "metric": "CHECK_FAILURE_RATE", "comparator": "LESS_THAN", "threshold": 0.01 }
    ],
    "steps": [
      {
        "request": { "method": "GET", "path": "/api/orders/123",
                     "socketAddress": { "host": "target", "port": 8080, "scheme": "HTTP" } },
        "checks": [
          { "source": "STATUS", "comparator": "EQUALS", "value": "200" },
          { "source": "HEADER", "headerName": "Content-Type", "comparator": "CONTAINS", "value": "application/json" },
          { "source": "BODY_JSONPATH", "jsonPath": "$.status", "comparator": "EQUALS", "value": "CONFIRMED" }
        ]
      }
    ]
}
JSON;

        $this->assertCount(3, $history);
        $this->assertSame('/mockserver/loadScenario', $history[0]['request']->getUri()->getPath());
        $this->assertSame(
            JsonCanon::decode($restTab),
            JsonCanon::decode((string) $history[0]['request']->getBody()),
        );
        $this->assertSame('/mockserver/loadScenario/start', $history[1]['request']->getUri()->getPath());
        $this->assertSame(
            ['names' => ['checked-scenario']],
            json_decode((string) $history[1]['request']->getBody(), true),
        );
        $this->assertSame('/mockserver/loadScenario/stop', $history[2]['request']->getUri()->getPath());
    }
}
