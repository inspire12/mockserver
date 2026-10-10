package org.mockserver.netty.integration;

import org.mockserver.integration.ClientAndServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Run only in a forked JVM against the shaded jar (never in the test JVM): embeds MockServer the way a
 * project depending on mockserver-netty-no-dependencies does. With the argument "logFirst" the
 * application logs through SLF4J before starting MockServer.
 */
public class EmbeddedMockServerMain {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("logFirst")) {
            org.slf4j.LoggerFactory.getLogger(EmbeddedMockServerMain.class).info("application log line");
        }
        ClientAndServer server = ClientAndServer.startClientAndServer(0);
        try {
            server.when(request("/embedded")).respond(response("embedded-ok"));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/embedded")).build(),
                HttpResponse.BodyHandlers.ofString());
            System.out.println("EMBEDDED RESULT " + response.statusCode() + " " + response.body());
        } finally {
            server.stop();
        }
        System.exit(0);
    }
}
