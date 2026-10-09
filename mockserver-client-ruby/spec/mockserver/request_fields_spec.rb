# frozen_string_literal: true

require 'json'

# Fields and input shapes the website examples use: the override-forwarded-request
# overrides, an OpenAPI matcher in httpRequest, keyMatchStyle, and keyToMultiValue
# collections given as a Hash.
RSpec.describe 'request fields used by the website examples' do
  def wire(model)
    JSON.parse(JSON.generate(model.to_h))
  end

  describe MockServer::HttpOverrideForwardedRequest do
    let(:override) do
      {
        'requestOverride' => { 'path' => '/other', 'headers' => [{ 'name' => 'Host', 'values' => ['target.host.com'] }] },
        'requestModifier' => { 'path' => { 'regex' => '^/(.+)$', 'substitution' => '/prefix/$1' } },
        'responseOverride' => { 'statusCode' => 202, 'body' => 'overridden' },
        'responseModifier' => { 'headers' => { 'remove' => ['Server'] } },
        'responseTemplate' => { 'templateType' => 'VELOCITY', 'template' => '{ "statusCode": 200 }' },
        'delay' => { 'timeUnit' => 'SECONDS', 'value' => 1 },
        'primary' => true
      }
    end

    it 'round-trips requestOverride, responseOverride and responseTemplate' do
      expect(wire(described_class.from_hash(override))).to eq(override)
    end

    it 'reads them inside an expectation' do
      expectation = { 'httpRequest' => { 'path' => '/some/path' }, 'httpOverrideForwardedRequest' => override }
      expect(wire(MockServer::Expectation.from_hash(expectation))).to eq(expectation)
    end

    it 'writes typed overrides under their wire names' do
      model = described_class.new(
        request_override: MockServer::HttpRequest.new(path: '/other'),
        response_override: MockServer::HttpResponse.new(status_code: 202),
        response_template: MockServer::HttpTemplate.new(template_type: 'MUSTACHE', template: '{}')
      )
      expect(wire(model)).to eq(
        'requestOverride' => { 'path' => '/other' },
        'responseOverride' => { 'statusCode' => 202 },
        'responseTemplate' => { 'templateType' => 'MUSTACHE', 'template' => '{}' }
      )
    end

    it 'sets the overrides with builder methods' do
      model = described_class.new
                             .with_request_override(MockServer::HttpRequest.new(path: '/a'))
                             .with_response_override(MockServer::HttpResponse.new(body: 'b'))
                             .with_response_template(MockServer::HttpTemplate.new(template: 'c'))
      expect(wire(model).keys).to eq(%w[requestOverride responseOverride responseTemplate])
    end

    it 'still round-trips the older httpRequest and httpResponse overrides' do
      older = { 'httpRequest' => { 'path' => '/a' }, 'httpResponse' => { 'statusCode' => 200 } }
      expect(wire(described_class.from_hash(older))).to eq(older)
    end
  end

  describe 'an OpenAPI request matcher in httpRequest' do
    let(:matcher) do
      { 'not' => true, 'specUrlOrPayload' => 'https://example.com/openapi.json',
        'operationId' => 'showPetById', 'contextPathPrefix' => '/v1' }
    end

    it 'reads an expectation matcher as an OpenAPIDefinition' do
      expectation = MockServer::Expectation.from_hash('httpRequest' => matcher, 'httpResponse' => { 'body' => 'x' })
      expect(expectation.http_request).to be_a(MockServer::OpenAPIDefinition)
      expect(expectation.http_request.operation_id).to eq('showPetById')
      expect(wire(expectation)).to eq('httpRequest' => matcher, 'httpResponse' => { 'body' => 'x' })
    end

    it 'accepts a spec given as an object' do
      spec = { 'openapi' => '3.0.0', 'paths' => {} }
      expectation = MockServer::Expectation.from_hash('httpRequest' => { 'specUrlOrPayload' => spec })
      expect(wire(expectation)).to eq('httpRequest' => { 'specUrlOrPayload' => spec })
    end

    it 'reads a verification matcher the same way' do
      verification = { 'httpRequest' => matcher, 'times' => { 'atLeast' => 1 } }
      expect(wire(MockServer::Verification.from_hash(verification))).to eq(verification)
    end

    it 'reads verifySequence matchers the same way' do
      sequence = { 'httpRequests' => [matcher, { 'path' => '/b' }] }
      model = MockServer::VerificationSequence.from_hash(sequence)
      expect(model.http_requests.map(&:class)).to eq([MockServer::OpenAPIDefinition, MockServer::HttpRequest])
      expect(wire(model)).to eq(sequence)
    end

    it 'leaves a plain request matcher an HttpRequest' do
      expectation = MockServer::Expectation.from_hash('httpRequest' => { 'path' => '/a' })
      expect(expectation.http_request).to be_a(MockServer::HttpRequest)
    end
  end

  describe 'keyMatchStyle' do
    %w[headers queryStringParameters pathParameters].each do |field|
      it "round-trips keyMatchStyle on #{field} instead of reading it as a #{field} entry" do
        request = { 'path' => '/some/path',
                    field => { 'keyMatchStyle' => 'MATCHING_KEY', 'one' => ['a.*'], 'two' => ['b'] } }
        model = MockServer::HttpRequest.from_hash(request)
        names = model.public_send(MockServer.from_camel(field)).map(&:name)
        expect(names).to eq(%w[one two])
        expect(wire(model)).to eq(request)
      end
    end

    it 'writes the object form when a key match style is set' do
      model = MockServer::HttpRequest.new(path: '/a', headers_key_match_style: 'MATCHING_KEY')
                                     .with_header('multiValuedHeader', 'value.*')
      expect(wire(model)['headers']).to eq('keyMatchStyle' => 'MATCHING_KEY', 'multiValuedHeader' => ['value.*'])
    end

    it 'merges repeated names in the object form' do
      model = MockServer::HttpRequest.new(query_string_parameters_key_match_style: 'MATCHING_KEY')
                                     .with_query_param('q', '1').with_query_param('q', '2')
      expect(wire(model)['queryStringParameters']).to eq('keyMatchStyle' => 'MATCHING_KEY', 'q' => %w[1 2])
    end

    it 'keeps the array form without a key match style' do
      model = MockServer::HttpRequest.new(path: '/a').with_query_param('q', '1')
      expect(wire(model)['queryStringParameters']).to eq([{ 'name' => 'q', 'values' => ['1'] }])
    end

    it 'does not turn keyMatchStyle into a response header' do
      response = MockServer::HttpResponse.from_hash('headers' => { 'keyMatchStyle' => 'SUB_SET', 'A' => ['b'] })
      expect(response.headers.map(&:name)).to eq(['A'])
    end
  end

  describe 'keyToMultiValue collections given as a Hash' do
    it 'accepts headers, query parameters and path parameters as { name => values }' do
      model = MockServer::HttpRequest.new(
        headers: { 'Accept' => ['application/json'], 'X-One' => 'single' },
        query_string_parameters: { cartId: '055CA455' },
        path_parameters: { 'id' => [{ 'schema' => { 'type' => 'integer' } }] }
      )
      expect(wire(model)).to eq(
        'headers' => [{ 'name' => 'Accept', 'values' => ['application/json'] },
                      { 'name' => 'X-One', 'values' => ['single'] }],
        'queryStringParameters' => [{ 'name' => 'cartId', 'values' => ['055CA455'] }],
        'pathParameters' => { 'id' => [{ 'schema' => { 'type' => 'integer' } }] }
      )
    end

    it 'reads keyMatchStyle from a Hash given to the constructor' do
      model = MockServer::HttpRequest.new(headers: { 'keyMatchStyle' => 'MATCHING_KEY', 'A' => 'b' })
      expect(model.headers_key_match_style).to eq('MATCHING_KEY')
      expect(wire(model)['headers']).to eq('keyMatchStyle' => 'MATCHING_KEY', 'A' => ['b'])
    end

    it 'reads keyMatchStyle given as a symbol key' do
      model = MockServer::HttpRequest.new(headers: { keyMatchStyle: 'MATCHING_KEY', 'X' => 'a' })
      expect(model.headers.map(&:name)).to eq(['X'])
      expect(wire(model)['headers']).to eq('keyMatchStyle' => 'MATCHING_KEY', 'X' => ['a'])
    end

    it 'accepts an Array of name/values Hashes with symbol or string keys' do
      model = MockServer::HttpRequest.new(
        query_string_parameters: [{ name: 'cartId', values: ['1'] }],
        headers: [{ 'name' => 'A', 'values' => %w[b c] }, MockServer::KeyToMultiValue.new(name: 'D', values: ['e'])]
      )
      expect(wire(model)['queryStringParameters']).to eq([{ 'name' => 'cartId', 'values' => ['1'] }])
      expect(wire(model)['headers']).to eq([{ 'name' => 'A', 'values' => %w[b c] }, { 'name' => 'D', 'values' => ['e'] }])
    end

    it 'accepts request cookies as { name => value } or an Array of Hashes' do
      from_hash = MockServer::HttpRequest.new(cookies: { 'session' => '4930456C' })
      from_list = MockServer::HttpRequest.new(cookies: [{ name: 'session', values: ['4930456C'] }])
      from_value = MockServer::HttpRequest.new(cookies: [{ 'name' => 'session', 'value' => '4930456C' }])
      [from_hash, from_list, from_value].each do |model|
        expect(wire(model)['cookies']).to eq('session' => '4930456C')
      end
    end

    it 'accepts response headers, trailers and cookies as a Hash' do
      model = MockServer::HttpResponse.new(
        headers: { 'Content-Type' => 'application/json' },
        trailers: { 'X-Checksum' => ['abc123'] },
        cookies: { 'Session' => '97d43b1e' }
      )
      expect(wire(model)).to eq(
        'headers' => [{ 'name' => 'Content-Type', 'values' => ['application/json'] }],
        'trailers' => [{ 'name' => 'X-Checksum', 'values' => ['abc123'] }],
        'cookies' => { 'Session' => '97d43b1e' }
      )
    end

    it 'coerces a Hash assigned through the setters too' do
      request = MockServer::HttpRequest.new
      request.headers = { 'A' => 'b' }
      response = MockServer::HttpResponse.new
      response.cookies = { 'c' => 'd' }
      expect(wire(request)['headers']).to eq([{ 'name' => 'A', 'values' => ['b'] }])
      expect(wire(response)['cookies']).to eq('c' => 'd')
    end

    it 'rejects a value that is neither a Hash nor an Array' do
      expect { MockServer::HttpRequest.new(headers: 'Accept: text/plain') }.to raise_error(TypeError)
      expect { MockServer::HttpResponse.new(cookies: 42) }.to raise_error(TypeError)
    end
  end
end
