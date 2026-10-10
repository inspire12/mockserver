package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.io.Writer;
import java.util.List;

/**
 * Generates copy-paste-ready JavaScript/TypeScript expectation code for the
 * MockServer Node.js client ({@code mockserver-client}).
 * <p>
 * Unlike the Java client (which requires the typed builder DSL, hence the
 * {@code ExpectationToJavaSerializer} family), the Node client accepts an
 * expectation as a plain JSON object via {@code mockAnyResponse(...)}. So the
 * "code" for JavaScript is simply each expectation's existing JSON
 * serialization embedded in a client call, preceded by the require preamble.
 * <p>
 * Example output for a single expectation:
 * <pre>
 * const { mockServerClient } = require('mockserver-client');
 *
 * mockServerClient("localhost", 1080).mockAnyResponse({
 *   "httpRequest" : { ... },
 *   "httpResponse" : { ... }
 * });
 * </pre>
 * One {@code mockAnyResponse(...)} call is emitted per expectation.
 *
 * @author jamesdbloom
 */
public class ExpectationToJavaScriptSerializer {

    private static final String NEW_LINE = "\n";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToJavaScriptSerializer(ExpectationSerializer expectationSerializer) {
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
        output.append("const { mockServerClient } = require('mockserver-client');").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                GeneratedCode.flush(output, writer);
                if (expectation == null) {
                    continue;
                }
                output.append(NEW_LINE);
                output.append("mockServerClient(\"localhost\", 1080).mockAnyResponse(");
                GeneratedCode.flush(output, writer);
                expectationSerializer.serialize(expectation, writer);
                output.append(");").append(NEW_LINE);
            }
        }
        GeneratedCode.flush(output, writer);
    }
}
