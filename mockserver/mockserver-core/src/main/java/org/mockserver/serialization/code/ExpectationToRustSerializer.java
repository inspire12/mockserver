package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.io.Writer;
import java.util.List;

/**
 * Generates copy-paste-ready Rust expectation code for the MockServer Rust
 * client ({@code mockserver-client} crate, module {@code mockserver_client}).
 * <p>
 * Following the same JSON-wrap pattern as the JavaScript/Python generators, the
 * generated code embeds each expectation's existing JSON serialization (the
 * same bytes produced by {@code format=JSON}) in a Rust raw string literal and
 * deserializes it with {@code serde_json::from_str::<Expectation>(...)}, then
 * passes it to {@code client.upsert(&[...])}.
 * <p>
 * Verified against the Rust client source:
 * <ul>
 *   <li>{@code ClientBuilder::new(host, port).build()?} → {@code Result<MockServerClient>}</li>
 *   <li>{@code pub fn upsert(&self, expectations: &[Expectation]) -> Result<Vec<Expectation>>}</li>
 *   <li>{@code Expectation} derives {@code Deserialize} (so {@code serde_json::from_str} works)</li>
 * </ul>
 * <p>
 * The JSON is embedded in a Rust raw string literal {@code r#"..."#}. A raw
 * literal opened with N {@code #} hashes is terminated by a double-quote
 * followed by N hashes, so the generator chooses the smallest N such that the
 * JSON never contains {@code "} followed by N {@code #} characters, guaranteeing
 * the literal cannot be terminated early by expectation content.
 * Example output:
 * <pre>
 * use mockserver_client::{ClientBuilder, Expectation};
 *
 * fn main() -&gt; Result&lt;(), Box&lt;dyn std::error::Error&gt;&gt; {
 *     let client = ClientBuilder::new("localhost", 1080).build()?;
 *
 *     client.upsert(&amp;[serde_json::from_str::&lt;Expectation&gt;(r#"{ ... }"#)?])?;
 *
 *     Ok(())
 * }
 * </pre>
 *
 * @author jamesdbloom
 */
public class ExpectationToRustSerializer {

    private static final String NEW_LINE = "\n";
    private static final String INDENT = "    ";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToRustSerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        return GeneratedCode.toString(writer -> serialize(expectations, writer));
    }

    /**
     * As {@link #serialize(List)}, writing the code for one expectation at a time to {@code writer}.
     */
    public void serialize(List<Expectation> expectations, Writer writer) {
        StringBuilder output = new StringBuilder();
        output.append("use mockserver_client::{ClientBuilder, Expectation};").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("fn main() -> Result<(), Box<dyn std::error::Error>> {").append(NEW_LINE);
        output.append(INDENT).append("let client = ClientBuilder::new(\"localhost\", 1080).build()?;").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                GeneratedCode.flush(output, writer);
                if (expectation == null) {
                    continue;
                }
                String hashes = rawStringHashes(expectation);
                output.append(NEW_LINE);
                output.append(INDENT)
                    .append("client.upsert(&[serde_json::from_str::<Expectation>(r")
                    .append(hashes).append("\"");
                GeneratedCode.flush(output, writer);
                expectationSerializer.serialize(expectation, writer);
                output.append("\"").append(hashes)
                    .append(")?])?;").append(NEW_LINE);
            }
        }
        output.append(NEW_LINE);
        output.append(INDENT).append("Ok(())").append(NEW_LINE);
        output.append("}").append(NEW_LINE);
        GeneratedCode.flush(output, writer);
    }

    /**
     * Choose the smallest run of {@code #} hashes such that a raw string literal
     * {@code r<hashes>"..."<hashes>} cannot be terminated early by the JSON: the
     * literal ends at a {@code "} followed by that many {@code #}. That is one more
     * than the longest run of {@code #} after a {@code "} in the JSON, which is
     * written once to find it rather than held whole.
     */
    private String rawStringHashes(Expectation expectation) {
        int[] longestRun = new int[1];
        expectationSerializer.serialize(expectation, new GeneratedCode.CharWriter() {
            // -1 until a quote is seen, then the number of hashes after it
            private int run = -1;

            @Override
            public void write(int c) {
                if (c == '"') {
                    run = 0;
                } else if (c == '#' && run >= 0) {
                    longestRun[0] = Math.max(longestRun[0], ++run);
                } else {
                    run = -1;
                }
            }
        });
        StringBuilder sb = new StringBuilder(longestRun[0] + 1);
        for (int i = 0; i <= longestRun[0]; i++) {
            sb.append('#');
        }
        return sb.toString();
    }
}
