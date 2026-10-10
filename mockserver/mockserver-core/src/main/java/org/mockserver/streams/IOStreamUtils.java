package org.mockserver.streams;

import com.google.common.io.ByteStreams;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.SegmentedBytes;
import org.slf4j.event.Level;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockserver.character.Character.NEW_LINE;

/**
 * @author jamesdbloom
 */
public class IOStreamUtils {
    private final MockServerLogger mockServerLogger;

    public IOStreamUtils(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public static String readHttpInputStreamToString(Socket socket) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder result = new StringBuilder();
            String line;
            Integer contentLength = null;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.startsWith("content-length") || line.startsWith("Content-Length")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
                if (line.length() == 0) {
                    if (contentLength != null) {
                        result.append(NEW_LINE);
                        for (int position = 0; position < contentLength; position++) {
                            result.append((char) bufferedReader.read());
                        }
                    }
                    break;
                }
                result.append(line).append(NEW_LINE);
            }
            return result.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readSocketToString(Socket socket) {
        StringBuilder result = new StringBuilder();
        try {
            InputStream inputStream = socket.getInputStream();
            do {
                final byte[] buffer = new byte[10000];
                final int readBytes = inputStream.read(buffer);
                result.append(new String(
                    Arrays.copyOfRange(buffer, 0, readBytes),
                    StandardCharsets.UTF_8
                ));
            } while (inputStream.available() > 0);
            return result.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String readHttpInputStreamToString(ServletRequest request) {
        try {
            return new String(ByteStreams.toByteArray(request.getInputStream()), request.getCharacterEncoding() != null ? request.getCharacterEncoding() : UTF_8.name());
        } catch (IOException ioe) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("IOException while reading HttpServletRequest input stream")
                    .setThrowable(ioe)
            );
            throw new RuntimeException("IOException while reading HttpServletRequest input stream", ioe);
        }
    }

    public void writeToOutputStream(SegmentedBytes data, ServletResponse response) {
        try {
            OutputStream output = response.getOutputStream();
            data.writeTo(output);
            output.close();
        } catch (IOException ioe) {
            ByteBuffer[] buffers = data.asByteBuffers();
            ByteBuffer first = buffers.length > 0 ? buffers[0] : ByteBuffer.allocate(0);
            throw writeFailure(first.array(), first.remaining(), data.size(), ioe);
        }
    }

    public void writeToOutputStream(byte[] data, ServletResponse response) {
        try {
            OutputStream output = response.getOutputStream();
            output.write(data);
            output.close();
        } catch (IOException ioe) {
            throw writeFailure(data, data.length, data.length, ioe);
        }
    }

    /**
     * @param start an array that begins with the data, of which {@code available} bytes are the data
     */
    private RuntimeException writeFailure(byte[] start, int available, int length, IOException ioe) {
        String truncatedContent = length > 100
            ? new String(start, 0, Math.min(100, available), UTF_8) + "...(" + length + " bytes)"
            : new String(start, 0, available, UTF_8);
        String sanitized = truncatedContent.replaceAll("[<>&]", "_");
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.ERROR)
                .setMessageFormat("IOException while writing [{}] to HttpServletResponse output stream")
                .setArguments(SensitiveLogValue.of(sanitized))
                .setThrowable(ioe)
        );
        return new RuntimeException("IOException while writing " + length + " bytes to HttpServletResponse output stream", ioe);
    }

}
