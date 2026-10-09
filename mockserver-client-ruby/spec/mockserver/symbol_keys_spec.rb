# frozen_string_literal: true

require 'json'

# A hash literal written with JSON-style keys ({ "httpRequest": { ... } }) has
# symbol keys in Ruby. from_hash must read it exactly as the string-keyed hash
# JSON.parse returns, not silently build an empty model.
RSpec.describe 'from_hash with symbol keys' do
  fixture_dir = File.expand_path('../../../test-fixtures/expectations', __dir__)
  fixtures = Dir[File.join(fixture_dir, '*.json')].reject { |f| f.end_with?('known-gaps.json') }.sort

  it 'has expectation fixtures to sweep' do
    expect(fixtures.length).to be > 20
  end

  fixtures.each do |path|
    it "reads #{File.basename(path)} the same with symbol keys as with string keys" do
      raw = File.read(path)
      parsed = JSON.parse(raw)
      symbolised = JSON.parse(raw, symbolize_names: true)
      list = parsed.is_a?(Array) ? parsed : [parsed]
      symbol_list = symbolised.is_a?(Array) ? symbolised : [symbolised]

      list.zip(symbol_list).each do |string_keys, symbol_keys|
        want = JSON.parse(JSON.generate(MockServer::Expectation.from_hash(string_keys).to_h))
        got = JSON.parse(JSON.generate(MockServer::Expectation.from_hash(symbol_keys).to_h))
        expect(got).not_to be_empty
        expect(got).to eq(want)
      end
    end
  end

  it 'reads the JSON-style literal used in the documentation' do
    expectation = MockServer::Expectation.from_hash({
      "httpRequest": { "path": '/some/path', "headers": { "Accept": ['application/json'] } },
      "httpResponse": { "statusCode": 200, "body": { "type": 'JSON', "json": { "id": 1 } } },
      "times": { "remainingTimes": 1 }
    })

    expect(expectation.http_request.path).to eq('/some/path')
    expect(expectation.http_request.headers.map(&:to_h)).to eq([{ 'name' => 'Accept', 'values' => ['application/json'] }])
    expect(expectation.http_response.status_code).to eq(200)
    expect(expectation.http_response.body).to be_a(MockServer::Body)
    expect(expectation.http_response.body.to_h).to eq({ 'type' => 'JSON', 'json' => { 'id' => 1 } })
    expect(expectation.times.remaining_times).to eq(1)
  end

  it 'reads symbol keys nested under string keys' do
    expectation = MockServer::Expectation.from_hash(
      'httpRequest' => { 'path' => '/p', 'body' => { type: 'STRING', string: 'abc' } },
      'httpResponse' => { statusCode: 418 }
    )

    expect(expectation.http_request.body.to_h).to eq({ 'type' => 'STRING', 'string' => 'abc' })
    expect(expectation.http_response.status_code).to eq(418)
  end

  it 'leaves values, and keys that are not symbols, unchanged' do
    expectation = MockServer::Expectation.from_hash(
      httpRequest: { path: '/p' },
      httpResponse: { body: { type: 'JSON', json: { 1 => 'one', 'two' => :two } } }
    )

    expect(expectation.http_response.body.json).to eq({ 1 => 'one', 'two' => :two })
  end

  it 'does not modify the caller hash' do
    input = { httpRequest: { path: '/p' } }
    MockServer::Expectation.from_hash(input)

    expect(input).to eq({ httpRequest: { path: '/p' } })
  end

  it 'reads symbol keys for models parsed on their own' do
    verification = MockServer::Verification.from_hash(
      httpRequest: { path: '/v' }, times: { atLeast: 2 }
    )
    sequence = MockServer::VerificationSequence.from_hash(httpRequests: [{ path: '/a' }, { path: '/b' }])
    request = MockServer::HttpRequest.from_hash(method: 'GET', path: '/r')

    expect(verification.http_request.path).to eq('/v')
    expect(verification.times.at_least).to eq(2)
    expect(sequence.http_requests.map(&:path)).to eq(['/a', '/b'])
    expect(request.to_h).to eq({ 'method' => 'GET', 'path' => '/r' })
  end

  it 'reads symbol keys in every model that has from_hash' do
    models = MockServer.constants.map { |c| MockServer.const_get(c) }
                       .select { |k| k.is_a?(Class) && k.respond_to?(:from_hash) }.uniq
    expect(models.length).to be > 50

    models.each do |model|
      string_keyed = model.from_hash({ 'id' => 'x', 'name' => 'n', 'path' => '/p', 'type' => 'STRING' })
      symbol_keyed = model.from_hash({ id: 'x', name: 'n', path: '/p', type: 'STRING' })
      next if string_keyed.nil? || !string_keyed.respond_to?(:to_h)

      expect(JSON.generate(symbol_keyed.to_h)).to eq(JSON.generate(string_keyed.to_h)), model.name
    end
  end
end
