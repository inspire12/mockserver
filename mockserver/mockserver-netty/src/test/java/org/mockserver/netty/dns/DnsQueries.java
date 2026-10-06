package org.mockserver.netty.dns;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;

/**
 * Asks a DNS server on {@code 127.0.0.1} the way a client of MockServer would.
 */
public final class DnsQueries {

    private static final int QUERY_TIMEOUT_MILLIS = 5000;

    private DnsQueries() {
    }

    /**
     * @return the address in the A record answered for {@code name}, or why there was none
     */
    public static String addressAnsweredFor(String name, int dnsPort) throws Exception {
        byte[] query = Message.newQuery(org.xbill.DNS.Record.newRecord(Name.fromString(name), Type.A, DClass.IN)).toWire();
        try (DatagramSocket resolver = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            resolver.setSoTimeout(QUERY_TIMEOUT_MILLIS);
            resolver.connect(new InetSocketAddress("127.0.0.1", dnsPort));
            resolver.send(new DatagramPacket(query, query.length));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            resolver.receive(reply);
            List<org.xbill.DNS.Record> answers = new Message(Arrays.copyOf(reply.getData(), reply.getLength())).getSection(Section.ANSWER);
            return answers.size() == 1 && answers.get(0) instanceof ARecord ? ((ARecord) answers.get(0)).getAddress().getHostAddress() : "answers " + answers;
        } catch (SocketTimeoutException | PortUnreachableException | org.xbill.DNS.WireParseException notThisServer) {
            return "nothing (" + notThisServer + ")";
        }
    }
}
