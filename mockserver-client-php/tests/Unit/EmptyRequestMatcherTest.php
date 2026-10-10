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
use MockServer\MockServerClient;
use MockServer\VerificationTimes;
use PHPUnit\Framework\TestCase;

/**
 * An empty request matcher matches every request; it must reach the server
 * as a JSON object, not as the empty JSON array PHP encodes [] as.
 */
class EmptyRequestMatcherTest extends TestCase
{
    /**
     * @param array<array> &$history
     */
    private function client(array &$history, Response ...$responses): MockServerClient
    {
        $stack = HandlerStack::create(new MockHandler($responses ?: [new Response(201, [], '[]'), new Response(202, [])]));
        $stack->push(Middleware::history($history));
        $client = new MockServerClient('localhost', 1080);
        $prop = (new \ReflectionClass($client))->getProperty('httpClient');
        $prop->setValue($client, new GuzzleClient(['handler' => $stack, 'http_errors' => false]));
        return $client;
    }

    public function testAnExpectationWithAnEmptyRequestSendsAnObject(): void
    {
        $history = [];
        $this->client($history)->when(HttpRequest::request())->respond(HttpResponse::response()->body('x'));

        $sent = (string) $history[0]['request']->getBody();
        $this->assertStringContainsString('"httpRequest":{}', $sent);
    }

    public function testAVerificationWithAnEmptyRequestSendsAnObject(): void
    {
        $history = [];
        $this->client($history)->verify(HttpRequest::request(), VerificationTimes::exactly(0));

        $sent = (string) $history[0]['request']->getBody();
        $this->assertStringContainsString('"httpRequest":{}', $sent);
    }

    public function testClearRetrieveAndVerifySequenceWithAnEmptyRequestSendAnObject(): void
    {
        $history = [];
        $client = $this->client($history, new Response(200), new Response(202), new Response(200, [], '[]'));
        $client->clear(HttpRequest::request());
        $client->verifySequence(HttpRequest::request()->path('/a'), HttpRequest::request());
        $client->retrieveRecordedRequests(HttpRequest::request());

        $this->assertSame('{}', (string) $history[0]['request']->getBody());
        $this->assertStringContainsString('"httpRequests":[{"path":"\/a"},{}]', (string) $history[1]['request']->getBody());
        $this->assertSame('{}', (string) $history[2]['request']->getBody());
    }
}
