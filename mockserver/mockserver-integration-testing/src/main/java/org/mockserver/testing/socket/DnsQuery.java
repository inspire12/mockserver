package org.mockserver.testing.socket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Asks a DNS server on {@code 127.0.0.1} for the A record of a name, as a client of MockServer's DNS mock would,
 * written out on the wire so that a module needs no DNS library to check a DNS port it was given.
 */
public final class DnsQuery {

    private static final int QUERY_TIMEOUT_MILLIS = 5000;
    private static final int TYPE_A = 1;
    private static final int CLASS_IN = 1;

    private DnsQuery() {
    }

    /**
     * @return the address in the only A record answered for {@code name}, or why there was none
     */
    public static String addressAnsweredFor(String name, int dnsPort) throws IOException {
        byte[] query = query(name);
        try (DatagramSocket resolver = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            resolver.setSoTimeout(QUERY_TIMEOUT_MILLIS);
            resolver.connect(new InetSocketAddress("127.0.0.1", dnsPort));
            resolver.send(new DatagramPacket(query, query.length));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            resolver.receive(reply);
            return answeredAddress(ByteBuffer.wrap(reply.getData(), 0, reply.getLength()));
        } catch (SocketTimeoutException | PortUnreachableException notThisServer) {
            return "nothing (" + notThisServer + ")";
        }
    }

    private static byte[] query(String name) {
        ByteArrayOutputStream query = new ByteArrayOutputStream();
        // id, flags with recursion desired, one question, no other records
        writeShorts(query, 0x2a2a, 0x0100, 1, 0, 0, 0);
        for (String label : name.split("\\.")) {
            if (!label.isEmpty()) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                query.write(bytes.length);
                query.write(bytes, 0, bytes.length);
            }
        }
        query.write(0);
        writeShorts(query, TYPE_A, CLASS_IN);
        return query.toByteArray();
    }

    private static String answeredAddress(ByteBuffer reply) {
        reply.position(2);
        int responseCode = reply.getShort() & 0x0F;
        int questions = reply.getShort() & 0xFFFF;
        int answers = reply.getShort() & 0xFFFF;
        reply.position(12);
        if (responseCode != 0 || answers != 1) {
            return "answers " + answers + " with response code " + responseCode;
        }
        for (int question = 0; question < questions; question++) {
            skipName(reply);
            reply.position(reply.position() + 4);
        }
        skipName(reply);
        int type = reply.getShort() & 0xFFFF;
        reply.position(reply.position() + 6);
        int length = reply.getShort() & 0xFFFF;
        if (type != TYPE_A || length != 4) {
            return "an answer of type " + type;
        }
        return (reply.get() & 0xFF) + "." + (reply.get() & 0xFF) + "." + (reply.get() & 0xFF) + "." + (reply.get() & 0xFF);
    }

    private static void skipName(ByteBuffer reply) {
        while (true) {
            int length = reply.get() & 0xFF;
            if (length == 0) {
                return;
            }
            if ((length & 0xC0) == 0xC0) {
                reply.get();
                return;
            }
            reply.position(reply.position() + length);
        }
    }

    private static void writeShorts(ByteArrayOutputStream out, int... values) {
        for (int value : values) {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }
}
