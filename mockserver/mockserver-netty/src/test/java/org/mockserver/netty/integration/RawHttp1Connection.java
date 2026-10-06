package org.mockserver.netty.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One keep-alive HTTP/1.1 connection, written and read as raw bytes, so a test controls exactly how a body is
 * chunked on the wire.
 */
public final class RawHttp1Connection implements AutoCloseable {

    private final Socket socket = new Socket();
    private final InputStream in;
    private final OutputStream out;

    public RawHttp1Connection(int port) throws IOException {
        socket.connect(new InetSocketAddress("localhost", port), 5_000);
        // a hang guard: the build's leak detector makes the server slow to read a body of tiny chunks
        socket.setSoTimeout(240_000);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public void sendGet(String path) throws IOException {
        send("GET", path);
    }

    public void send(String method, String path) throws IOException {
        out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * A POST whose body is sent in chunks of the pattern's sizes, repeated, in writes of about 64 KiB.
     */
    public void sendChunked(String path, String body, int[] pattern) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.writeBytes(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        writeChunks(wire, body.getBytes(StandardCharsets.US_ASCII), pattern, this::writeIgnoringAClosedConnection);
        writeIgnoringAClosedConnection(wire);
    }

    /**
     * Appends {@code body} to {@code wire} as chunks of the pattern's sizes, repeated, and the last chunk, calling
     * {@code flush} whenever about 64 KiB is buffered.
     */
    public static void writeChunks(ByteArrayOutputStream wire, byte[] body, int[] pattern, Flush flush) throws IOException {
        int offset = 0;
        for (int piece = 0; offset < body.length; piece++) {
            int length = Math.min(pattern[piece % pattern.length], body.length - offset);
            wire.writeBytes((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            wire.write(body, offset, length);
            wire.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            offset += length;
            if (wire.size() >= 64 * 1024) {
                flush.flush(wire);
            }
        }
        wire.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    public interface Flush {
        void flush(ByteArrayOutputStream wire) throws IOException;
    }

    /**
     * A server that answers 413 closes the connection while the rest of the body may still be being written.
     */
    private void writeIgnoringAClosedConnection(ByteArrayOutputStream wire) throws IOException {
        try {
            out.write(wire.toByteArray());
            out.flush();
        } catch (SocketException closed) {
            // the response says why
        }
        wire.reset();
    }

    public Response readResponse() throws IOException {
        String head = readHead();
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        int contentLength = 0;
        boolean chunked = false;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        byte[] body = chunked ? readChunkedBody() : in.readNBytes(contentLength);
        return new Response(status, head, new String(body, StandardCharsets.UTF_8));
    }

    private byte[] readChunkedBody() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int size = Integer.parseInt(readLine().split(";")[0].trim(), 16);
            if (size == 0) {
                while (!readLine().isEmpty()) {
                    // trailers
                }
                return body.toByteArray();
            }
            body.writeBytes(in.readNBytes(size));
            readLine();
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = in.read()) != -1) {
            if (character == '\n' && line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                return line.substring(0, line.length() - 1);
            }
            line.append((char) character);
        }
        throw new IOException("connection closed mid-line, received: " + line);
    }

    /**
     * Reads a response's head and nothing after it, as for a response to {@code HEAD}.
     */
    public String readHead() throws IOException {
        StringBuilder head = new StringBuilder();
        int character;
        while ((character = in.read()) != -1) {
            head.append((char) character);
            if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
                return head.substring(0, head.length() - 4);
            }
        }
        throw new IOException("connection closed before a response head, received: " + head);
    }

    public boolean isClosedByServer() throws IOException {
        socket.setSoTimeout(10_000);
        try {
            return in.read() == -1;
        } catch (SocketTimeoutException timeout) {
            return false;
        } catch (SocketException reset) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static final class Response {
        public final int status;
        public final String head;
        public final String body;

        Response(int status, String head, String body) {
            this.status = status;
            this.head = head;
            this.body = body;
        }
    }
}
