package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import org.mockserver.model.BinaryMessage;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Follows what a PostgreSQL server (frontend/backend protocol version 3) sends on a relayed connection, to tell which
 * forwarded message each byte answers and where each reply ends, so a reply can be dropped or replaced. Nothing is
 * held: each read is handed on in pieces, each piece with the reply it belongs to.
 * <p>
 * A reply ends where the protocol says the server is waiting for the client again, not at a message: after one byte
 * for SSLRequest and GSSENCRequest; at ReadyForQuery for a simple Query, a FunctionCall and a Sync; for the startup
 * message and a password or SASL message, at ReadyForQuery, an ErrorResponse, or an authentication request the client
 * must answer. Parse, Bind, Describe, Execute, Close and Flush have no reply of their own: those up to a Sync share
 * the Sync's (or a Query's or FunctionCall's sent before any Sync). CancelRequest, CopyData, CopyDone, CopyFail and Terminate get none, so what follows a CopyData belongs
 * to the Query that started the copy. Backend messages that answer no message (a notice between replies) are handed
 * on with no reply. One per relayed connection, used only on its event loop.
 */
final class PostgresqlReplies {

    static final int SSL_REQUEST_CODE = 80877103;
    static final int GSSENC_REQUEST_CODE = 80877104;
    static final int CANCEL_REQUEST_CODE = 80877102;
    private static final int TYPED_HEADER_BYTES = 5;
    private static final int AUTHENTICATION_HEADER_BYTES = 9;

    enum Ends {
        /** one byte: the answer to SSLRequest or GSSENCRequest */
        ONE_BYTE,
        /** at ReadyForQuery */
        READY_FOR_QUERY,
        /** at ReadyForQuery, an ErrorResponse, or an authentication request the client must answer */
        AUTHENTICATION,
        /** no reply: ends as soon as the replies before it have */
        NO_REPLY
    }

    /** Where what is read goes. */
    interface Output {
        /** Bytes of one read that belong to {@code reply}, or, when it is null, answer no tracked message. */
        void bytes(Reply reply, ByteBuf read, int index, int length);

        /** A reply to be dropped or replaced has ended; it is no longer pending. */
        void ended(Reply reply);
    }

    /** The upstream's reply to one or more forwarded messages, and what to do with it. */
    static final class Reply {
        private final Ends ends;
        private UpstreamReply handling;
        private BinaryMessage request;
        private String correlationId;
        private long dueNanos;
        // replies relayed as they are, back to back, are one entry
        private int count = 1;
        // extended-query messages not yet followed by their Sync
        private boolean openBatch;

        private Reply(Ends ends, UpstreamReply handling, BinaryMessage request, String correlationId, long dueNanos, boolean openBatch) {
            this.ends = ends;
            this.handling = handling;
            this.request = handling.relayed() ? null : request;
            this.correlationId = correlationId;
            this.dueNanos = dueNanos;
            this.openBatch = openBatch;
        }

        UpstreamReply handling() {
            return handling;
        }

        BinaryMessage request() {
            return request;
        }

        String correlationId() {
            return correlationId;
        }

        long dueNanos() {
            return dueNanos;
        }

        private void join(UpstreamReply next, BinaryMessage nextRequest, String nextCorrelationId, long nextDueNanos) {
            if (!next.relayed() && handling.relayed()) {
                request = nextRequest;
                correlationId = nextCorrelationId;
            }
            handling = handling.and(next);
            dueNanos = Math.max(dueNanos, nextDueNanos);
        }
    }

    private final Deque<Reply> pending = new ArrayDeque<>();
    private final byte[] header = new byte[AUTHENTICATION_HEADER_BYTES];
    private int headerBytes;
    private long bodyLeft;
    private boolean inMessage;
    private Reply owner;
    private int notRelayed;
    private boolean lost;
    // the read being handed on, and the run of it not yet handed on
    private ByteBuf currentRead;
    private Reply pieceOwner;
    private int pieceFrom;
    private int pieceTo;

    /**
     * Records a message just forwarded, in the order messages are written upstream. A message without a reply that is
     * to be replaced ends, and so has its replacement written, once the replies before it have.
     */
    void forwarded(BinaryMessage message, UpstreamReply handling, String correlationId, long dueNanos, Output output) {
        if (lost) {
            return;
        }
        byte[] bytes = message.getBytes();
        if (bytes.length == 0) {
            return;
        }
        byte type = bytes[0];
        Reply tail = pending.peekLast();
        if (type == 0) {
            int code = bytes.length >= 8 ? intAt(bytes, 4) : 0;
            if (code == SSL_REQUEST_CODE || code == GSSENC_REQUEST_CODE) {
                add(Ends.ONE_BYTE, handling, message, correlationId, dueNanos, false);
            } else if (code == CANCEL_REQUEST_CODE) {
                addWithoutReply(handling, message, correlationId, dueNanos, output);
            } else {
                add(Ends.AUTHENTICATION, handling, message, correlationId, dueNanos, false);
            }
            return;
        }
        switch (type) {
            case 'p':
                add(Ends.AUTHENTICATION, handling, message, correlationId, dueNanos, false);
                break;
            case 'P':
            case 'B':
            case 'D':
            case 'E':
            case 'C':
            case 'H':
                if (tail != null && tail.openBatch) {
                    joinOpenBatch(tail, handling, message, correlationId, dueNanos);
                } else {
                    add(Ends.READY_FOR_QUERY, handling, message, correlationId, dueNanos, true);
                }
                break;
            case 'S':
            case 'Q':
            case 'F':
                // a simple Query or FunctionCall after extended-query messages without a Sync shares their ReadyForQuery
                if (tail != null && tail.openBatch) {
                    joinOpenBatch(tail, handling, message, correlationId, dueNanos).openBatch = false;
                } else {
                    add(Ends.READY_FOR_QUERY, handling, message, correlationId, dueNanos, false);
                }
                break;
            default:
                addWithoutReply(handling, message, correlationId, dueNanos, output);
        }
    }

    /**
     * Hands on one read from the upstream, in order, in pieces: each the longest run of bytes that belongs to one
     * reply, or to none.
     */
    void read(ByteBuf read, Output output) {
        currentRead = read;
        int end = read.writerIndex();
        try {
            int i = read.readerIndex();
            while (i < end) {
                if (lost) {
                    piece(null, i, end, output);
                    break;
                }
                if (!inMessage) {
                    endRepliesWithoutReply(output);
                    Reply head = pending.peek();
                    if (head != null && head.ends == Ends.ONE_BYTE) {
                        piece(head, i, i + 1, output);
                        i++;
                        end(head, output);
                        continue;
                    }
                    inMessage = true;
                    owner = head;
                    headerBytes = 0;
                    bodyLeft = 0;
                }
                int start = i;
                boolean complete = false;
                while (i < end && !complete && !lost) {
                    if (headerBytes < TYPED_HEADER_BYTES) {
                        header[headerBytes++] = read.getByte(i++);
                        if (headerBytes == TYPED_HEADER_BYTES) {
                            int length = intAt(header, 1);
                            if (length < 4) {
                                piece(owner, start, i, output);
                                start = i;
                                lose();
                            }
                            bodyLeft = length - 4L;
                        }
                    } else if (header[0] == 'R' && headerBytes < AUTHENTICATION_HEADER_BYTES && bodyLeft > 0) {
                        // an authentication request's code says whether the client must answer it
                        header[headerBytes++] = read.getByte(i++);
                        bodyLeft--;
                    } else {
                        long skipped = Math.min(bodyLeft, end - i);
                        i += (int) skipped;
                        bodyLeft -= skipped;
                    }
                    complete = !lost && headerBytes >= TYPED_HEADER_BYTES && bodyLeft == 0;
                }
                if (lost) {
                    continue;
                }
                piece(owner, start, i, output);
                if (complete) {
                    inMessage = false;
                    Reply answered = owner;
                    owner = null;
                    if (answered != null && endsReply(answered.ends)) {
                        end(answered, output);
                    }
                }
            }
            if (!inMessage) {
                endRepliesWithoutReply(output);
            }
            flushPiece(output);
        } finally {
            currentRead = null;
        }
    }

    private void piece(Reply reply, int from, int to, Output output) {
        if (from == to) {
            return;
        }
        if (pieceTo > pieceFrom && pieceOwner == reply && pieceTo == from) {
            pieceTo = to;
            return;
        }
        flushPiece(output);
        pieceOwner = reply;
        pieceFrom = from;
        pieceTo = to;
    }

    private void flushPiece(Output output) {
        if (pieceTo > pieceFrom) {
            int from = pieceFrom;
            int to = pieceTo;
            pieceFrom = 0;
            pieceTo = 0;
            output.bytes(pieceOwner, currentRead, from, to - from);
        }
        pieceOwner = null;
    }

    /** How many replies pending are to be dropped or replaced. */
    int notRelayed() {
        return notRelayed;
    }

    /** Whether a malformed backend message stopped the tracking: everything is relayed from then on. */
    boolean lost() {
        return lost;
    }

    private boolean endsReply(Ends ends) {
        byte type = header[0];
        switch (ends) {
            case READY_FOR_QUERY:
                return type == 'Z';
            case AUTHENTICATION:
                return type == 'Z' || type == 'E' || type == 'R' && headerBytes == AUTHENTICATION_HEADER_BYTES && asksTheClient(intAt(header, 5));
            default:
                return false;
        }
    }

    /** Kerberos V5, cleartext, MD5, GSS, GSS continue, SSPI, SASL and SASL continue wait for the client; OK and SASL final do not. */
    private static boolean asksTheClient(int authenticationCode) {
        switch (authenticationCode) {
            case 2:
            case 3:
            case 5:
            case 7:
            case 8:
            case 9:
            case 10:
            case 11:
                return true;
            default:
                return false;
        }
    }

    private void add(Ends ends, UpstreamReply handling, BinaryMessage message, String correlationId, long dueNanos, boolean openBatch) {
        Reply tail = pending.peekLast();
        if (handling.relayed() && tail != null && tail.ends == ends && ends == Ends.READY_FOR_QUERY && tail.handling.relayed() && !tail.openBatch) {
            tail.count++;
            tail.openBatch = openBatch;
            return;
        }
        pending.add(new Reply(ends, handling, message, correlationId, dueNanos, openBatch));
        if (!handling.relayed()) {
            notRelayed++;
        }
    }

    private Reply joinOpenBatch(Reply tail, UpstreamReply handling, BinaryMessage message, String correlationId, long dueNanos) {
        if (handling.relayed() || !tail.handling.relayed()) {
            if (!handling.relayed()) {
                tail.join(handling, message, correlationId, dueNanos);
            }
            return tail;
        }
        if (tail.count == 1) {
            tail.join(handling, message, correlationId, dueNanos);
            notRelayed++;
            return tail;
        }
        // the batch was counted with relayed replies before it: it becomes an entry of its own
        tail.count--;
        tail.openBatch = false;
        Reply batch = new Reply(Ends.READY_FOR_QUERY, handling, message, correlationId, dueNanos, true);
        pending.add(batch);
        notRelayed++;
        return batch;
    }

    private void addWithoutReply(UpstreamReply handling, BinaryMessage message, String correlationId, long dueNanos, Output output) {
        if (handling.replacement() == null) {
            // nothing to drop and nothing to write
            return;
        }
        pending.add(new Reply(Ends.NO_REPLY, handling, message, correlationId, dueNanos, false));
        notRelayed++;
        if (!inMessage) {
            endRepliesWithoutReply(output);
        }
    }

    private void endRepliesWithoutReply(Output output) {
        Reply head;
        while ((head = pending.peek()) != null && head.ends == Ends.NO_REPLY) {
            end(head, output);
        }
    }

    private void end(Reply reply, Output output) {
        if (--reply.count > 0) {
            return;
        }
        // the bytes up to the end go first
        flushPiece(output);
        pending.poll();
        if (!reply.handling.relayed()) {
            notRelayed--;
            output.ended(reply);
        }
    }

    private void lose() {
        lost = true;
        inMessage = false;
        owner = null;
        pending.clear();
        notRelayed = 0;
    }

    private static int intAt(byte[] bytes, int index) {
        return (bytes[index] & 0xff) << 24 | (bytes[index + 1] & 0xff) << 16 | (bytes[index + 2] & 0xff) << 8 | bytes[index + 3] & 0xff;
    }
}
