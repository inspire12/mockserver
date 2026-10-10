# frozen_string_literal: true

require 'rbconfig'
require 'socket'
require 'stringio'
require 'timeout'
require 'tmpdir'
require_relative 'check_website_examples'
require_relative 'languages'

# Unit tests for the runner the Python, Go, .NET, Rust and PHP checks share:
# the capture server, one process per block, and how findings reach the check.
# A stand-in language whose blocks are Ruby scripts keeps them toolchain-free.
RSpec.describe 'website example check for other languages' do
  languages = WebsiteExamples::Languages

  scripts = Module.new do
    module_function

    def build(units, work)
      units.each { |u| File.write(File.join(work, "#{WebsiteExamples::Languages.block_id(u)}.rb"), WebsiteExamples::Languages.code(u)) }
      units.select { |u| WebsiteExamples::Languages.code(u).include?('BROKEN') }.to_h { |u| [u[:key], 'does not build'] }
    end

    def command(unit, work, _dir)
      [RbConfig.ruby, File.join(work, "#{WebsiteExamples::Languages.block_id(unit)}.rb")]
    end

    # The block runs under the spec's bundle, which needs the real HOME.
    def env(_work)
      { 'HOME' => Dir.home }
    end

    def error(output)
      output.lines.map(&:strip).reject(&:empty?).last
    end
  end
  scripts.const_set(:NAME, 'scripts')

  put = lambda do |path, body = nil|
    "require 'net/http'\nNet::HTTP.start('localhost', 1080) { |h| h.send_request('PUT', '#{path}', #{body.inspect}) }\n"
  end

  def page(code, rest)
    path = File.join(Dir.mktmpdir, 'page.html')
    File.write(path, <<~HTML)
      <button id="button_x" class="accordion">x</button>
      <div class="panel">
          <button class="accordion inner">Scripts</button>
          <div class="panel"><pre class="prettyprint lang-scripts code"><code class="code">#{CGI.escapeHTML(code)}</code></pre></div>
          <button class="accordion inner">REST API</button>
          <div class="panel"><pre class="prettyprint code"><code class="code">#{rest}</code></pre></div>
      </div>
    HTML
    path
  end

  define_method(:run_check) do |path|
    out = StringIO.new
    original = $stdout
    $stdout = out
    code = WebsiteExamples::Check.run([], files: [path], allow: {}, lang: 'scripts',
                                          runner: languages::ProcessRunner.new(scripts, work: Dir.mktmpdir))
    [code, out.string]
  ensure
    $stdout = original
  end

  let(:reset) { 'curl -v -X PUT "http://localhost:1080/mockserver/reset"' }

  it 'passes a block that sends what the REST API tab sends' do
    expect(run_check(page(put.call('/mockserver/reset'), reset)).first).to eq(0)
  end

  it 'fails a block that sends something else' do
    expect(run_check(page(put.call('/mockserver/clear'), reset))).to match([1, /DIFFERS/])
  end

  it 'fails a block that exits with an error after sending what the REST API tab sends' do
    expect(run_check(page("#{put.call('/mockserver/reset')}raise 'late'", reset))).to match([1, /RAISED .*late/])
  end

  it 'digests an error by its type, not by detail an operating system words its own way' do
    digest = lambda do |detail|
      run_check(page("#{put.call('/mockserver/reset')}raise IOError, 'refused (#{detail})'", reset)).last[/\[digest (\h+)\]/, 1]
    end
    expect(digest.call('os error 61')).to eq(digest.call('os error 111'))
  end

  it 'reports a block that does not build' do
    expect(run_check(page("#{put.call('/mockserver/reset')}# BROKEN", reset))).to match([1, /DOES_NOT_COMPILE .*does not build/])
  end

  it 'gives a block the file the REST API tab sends with -d @name' do
    rest = 'curl -v -X PUT "http://localhost:1080/mockserver/expectation" -d @expectations.json'
    code = "body = File.read('expectations.json')\n#{put.call('/mockserver/expectation').sub('nil', 'body')}"
    expect(run_check(page(code, rest)).first).to eq(0)
  end

  describe WebsiteExamples::Languages::CaptureServer do
    around do |example|
      @server = described_class.new
      example.run
    ensure
      @server.close
    end

    # Every read has a deadline, so a server that never answers fails the test.
    def exchange(request)
      Timeout.timeout(10) do
        TCPSocket.open('127.0.0.1', 1080) do |socket|
          socket.write(request)
          yield socket if block_given?
          socket.gets
        end
      end
    end

    it 'records a chunked body and answers like MockServer' do
      status = exchange("PUT /mockserver/expectation?x=1 HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n" \
                        "5\r\n[{}, \r\n2\r\n{}\r\n1\r\n]\r\n0\r\n\r\n")
      expect(status).to start_with('HTTP/1.1 201')
      expect(@server.drain).to eq([{ 'method' => 'PUT', 'path' => '/mockserver/expectation', 'query' => 'x=1',
                                     'body' => '[{}, {}]' }])
      expect(@server.drain).to eq([])
    end

    it 'closes a TLS handshake at once instead of waiting for a request line' do
      reply = begin
        exchange("\x16\x03\x01\x00\x05hello")
      rescue Errno::ECONNRESET
        :reset
      end
      expect([nil, :reset]).to include(reply)
      expect(@server.drain).to eq([])
    end

    it 'answers Expect: 100-continue before reading the body' do
      status = exchange("PUT /mockserver/clear HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\n") do |s|
        expect(s.gets).to start_with('HTTP/1.1 100')
        s.gets
        s.write('{}')
      end
      expect(status).to start_with('HTTP/1.1 200')
      expect(@server.drain.first['body']).to eq('{}')
    end
  end

  describe 'block sources' do
    it 'wraps a Go snippet in a package and reads the variables it never reads' do
      source = languages::Go.source("import \"fmt\"\n\nx := 1\nfmt.Println(1)\n", 'b_1', ['x'])
      expect(source).to start_with("package b_1\n\nimport \"fmt\"\n\nfunc Main() {\n")
      expect(source).to include("\t_ = x\n}")
    end

    it 'keeps a whole Go program as written, renaming its main' do
      source = languages::Go.source("package main\n\nfunc main() {\n}\n", 'b_1')
      expect(source).to eq("package b_1\n\nfunc Main() {\n}\n")
    end

    it 'hoists a .NET block\'s using directives above its Run method' do
      source = languages::CSharp.source("using System.Text;\nusing var http = new HttpClient();\n", 'b_1')
      expect(source).to start_with("using System.Text;\n\nnamespace WebsiteExamples.b_1\n")
      expect(source).to include("Run()\n{\nusing var http = new HttpClient();\n")
    end
  end
end
