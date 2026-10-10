package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.mockserver.socket.LingeringClose;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link HttpObjectAggregator} that, past its component limit, merges only the components added since its last
 * merge instead of consolidating the whole body (the rule the HTTP/3 accumulator uses), and can also copy long runs
 * of tiny pieces into 16 KiB blocks. Either way a body of one-byte pieces is copied about once rather than whole each
 * time it passes the limit.
 * <p>
 * Off until {@link #mergeNewComponentsOnly()} or {@link #coalesceSmallContent()}, it behaves exactly like
 * {@link HttpObjectAggregator}. {@link #mergeNewComponentsOnly()} turns on the merge rule alone: the HTTP/1.1-limit
 * aggregators (server connection, forward client) use it. {@link #coalesceSmallContent()} adds the blocks, for an
 * HTTP/2 stream's limit, a tenth of the connection's, which the merge rule alone would pass every 1,024 frames:
 * <ul>
 *     <li>the first 64 components, every piece of 1 KiB or more, and any run of fewer than 16 consecutive pieces
 *     under 1 KiB (a DATA frame cut short by the flow-control window, say) are kept as they arrive;</li>
 *     <li>each 16th piece of a run copies the run into the free tail of the current 16 KiB block (a new one once it
 *     is full), so each such byte is copied into a block once, the component bookkeeping is paid once per 16
 *     pieces, and at most one block per body is part-used.</li>
 * </ul>
 * A piece is usually a slice of a socket read (or of a decoder's cumulation, or of {@code SslHandler}'s output), and
 * the whole buffer stays allocated while any slice of it is held. So a block copy frees nothing while larger pieces
 * from the same reads are kept: blocks can hold about the body again on top of the reads, which is why the
 * HTTP/1.1-limit aggregators, which rarely reach their limit, do without them. And a client that puts each small
 * piece in a read filled with other requests' bytes, or pads a chunk-size line with an extension, could make a body
 * pin far more than its size. Both modes bound that: when a body moves on to a new buffer, the pieces it holds from
 * the previous one are merged into a buffer of their own (with {@link #coalesceSmallContent()}, into the current
 * block) if they are under half that buffer, and so are the pieces a block copy leaves of an earlier buffer once
 * they are under half of it. Every buffer a body pins is then at least half used, except the one it is reading now
 * and one part-used block. A body whose pieces fill their reads is never copied. A buffer is counted under what it
 * unwraps to, as its slices are, because an allocator may return it inside a leak-detection wrapper.
 * <p>
 * The oversized-body check is Netty's and sees every byte, because Netty appends each piece and a run is only
 * moved, never held back. The aggregator never owns a reference to a block: a block is reachable only through
 * slices held as components, so releasing the composite (or merging them) frees it.
 */
public class CoalescingHttpObjectAggregator extends HttpObjectAggregator {

    static final int BLOCK_BYTES = 16 * 1024;
    static final int SMALL_PIECE_BYTES = 1024;
    static final int RUN_PIECES = 16;
    static final int COALESCE_AFTER_COMPONENTS = 64;
    private static final FullHttpResponse TOO_LARGE_CLOSE = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);

    static {
        TOO_LARGE_CLOSE.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        TOO_LARGE_CLOSE.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    }

    private boolean coalescing;
    private boolean copyingRuns;
    private CompositeByteBuf composite;
    private ByteBuf block;
    private int blockFill;
    private int sliceStart;
    private int sliceOffset;
    private int runOffset;
    private int runPieces;
    private int merged;
    private Map<ByteBuf, Use> uses;
    private List<ByteBuf> eroded;
    private boolean tracking;
    private ByteBuf lastRoot;
    private Use lastUse;
    private HttpRequest requestBeingAggregated;

    public CoalescingHttpObjectAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    /**
     * Turns on the merge rule and block coalescing for this aggregator's bodies. Must be called before the aggregator
     * is added to a pipeline.
     */
    public void coalesceSmallContent() {
        coalescing = true;
        copyingRuns = true;
    }

    /**
     * Turns on only the merge rule: past the component limit, merge the components added since the last merge
     * rather than the whole body, and copy no runs into blocks (pieces of a mostly unused buffer are still merged).
     * Must be called before the aggregator is added to a pipeline.
     */
    public void mergeNewComponentsOnly() {
        coalescing = true;
        copyingRuns = false;
    }

    public boolean isCoalescingSmallContent() {
        return coalescing && copyingRuns;
    }

    public boolean isMergingNewComponentsOnly() {
        return coalescing && !copyingRuns;
    }

    /**
     * Swaps the composite Netty created, which would consolidate the whole body past the component limit, for one
     * with no limit of its own: {@link #limitComponents} keeps the limit instead. Only a body in such a composite is
     * coalesced, because Netty's own consolidation would release the block without this aggregator knowing.
     */
    @Override
    protected FullHttpMessage beginAggregation(HttpMessage start, ByteBuf content) throws Exception {
        reset(null);
        requestBeingAggregated = null;
        if (!coalescing || !(content instanceof CompositeByteBuf) || ((CompositeByteBuf) content).numComponents() != 0) {
            return begun(start, super.beginAggregation(start, content));
        }
        CompositeByteBuf unlimited = content.alloc().compositeBuffer(Integer.MAX_VALUE);
        content.release();
        try {
            FullHttpMessage aggregated = super.beginAggregation(start, unlimited);
            reset(unlimited);
            return begun(start, aggregated);
        } catch (Exception | Error e) {
            unlimited.release();
            throw e;
        }
    }

    private FullHttpMessage begun(HttpMessage start, FullHttpMessage aggregated) {
        requestBeingAggregated = start instanceof HttpRequest ? (HttpRequest) start : null;
        return aggregated;
    }

    /**
     * @return the head of the request whose body this aggregator is still waiting for, or {@code null}: Netty reports
     * such a request as a {@link io.netty.handler.codec.PrematureChannelClosureException} when the channel closes, and
     * this still answers while that exception is handled
     */
    public HttpRequest requestBeingAggregated() {
        return requestBeingAggregated;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            super.channelInactive(ctx);
        } finally {
            requestBeingAggregated = null;
        }
    }

    @Override
    protected void aggregate(FullHttpMessage aggregated, HttpContent content) throws Exception {
        int length = content.content().readableBytes();
        ByteBuf root = composite != null && length > 0 ? root(content.content()) : null;
        super.aggregate(aggregated, content);
        CompositeByteBuf target = composite;
        if (target == null || aggregated.content() != target || length == 0) {
            return;
        }
        if (tracking && lastComponentHolds(target, length)) {
            tracked(target, root, length);
        } else {
            tracking = false;
        }
        if (!copyingRuns) {
            if (target.numComponents() > maxCumulationBufferComponents()) {
                limitComponents(target);
            }
        } else {
            appended(target, length);
        }
        freeEroded(target);
    }

    /**
     * Records the piece just appended; when it is the first from a new buffer, merges the pieces held from the
     * previous buffer if they are under half of it, so that buffer is freed rather than pinned.
     */
    private void tracked(CompositeByteBuf target, ByteBuf root, int length) {
        if (root == lastRoot) {
            lastUse.pieces++;
            lastUse.held += length;
            return;
        }
        ByteBuf previous = lastRoot;
        Use previousUse = lastUse;
        lastRoot = root;
        lastUse = use(root, false);
        lastUse.pieces++;
        lastUse.held += length;
        if (previous != null && previousUse.pieces > 0 && underHalf(previous, previousUse)) {
            mergePiecesOf(target, previous, previousUse.pieces);
        }
    }

    /**
     * Copies the components holding {@code root}'s pieces, which come just before the last component, into buffers of
     * their own, one per run of adjacent components; block slices a run copy put among them are stepped over, so
     * nothing but those pieces is copied, and byte offsets, so the block's last slice, are unchanged.
     */
    private void mergePiecesOf(CompositeByteBuf target, ByteBuf root, int pieces) {
        int index = target.numComponents() - 2;
        while (pieces > 0 && index >= merged) {
            ByteBuf candidate = root(target.internalComponent(index));
            if (candidate == root) {
                int end = index;
                while (index > merged && root(target.internalComponent(index - 1)) == root) {
                    index--;
                }
                pieces -= end - index + 1;
                copyComponents(target, index, end);
            } else if (!isBlock(candidate)) {
                return;
            }
            index--;
        }
    }

    private void copyComponents(CompositeByteBuf target, int start, int end) {
        if (copyingRuns && end == target.numComponents() - 2 && start >= 1) {
            // onto the current block, so these pieces are copied once, not again by the run they may belong to; a new
            // block is no larger than the body so far, so a short body is not given a whole 16 KiB block
            endRun();
            runOffset = target.toByteIndex(start);
            copyIntoBlocks(target, start, end + 1, Math.min(BLOCK_BYTES, target.capacity()));
            return;
        }
        copyIntoOwnBuffer(target, start, end);
    }

    private void copyIntoOwnBuffer(CompositeByteBuf target, int start, int end) {
        int from = target.toByteIndex(start);
        int bytes = target.toByteIndex(end + 1) - from;
        // allocate before changing the body; consolidate() would leave a single component in place
        ByteBuf copy = target.alloc().buffer(bytes, bytes);
        try {
            target.getBytes(from, copy, 0, bytes);
            copy.writerIndex(bytes);
        } catch (RuntimeException | Error e) {
            copy.release();
            throw e;
        }
        untrack(target, start, end);
        target.removeComponents(start, end - start + 1);
        target.addComponent(false, start, copy);
        target.writerIndex(target.capacity());
        track(root(copy), bytes);
    }

    /**
     * Copies the pieces left of each buffer a block copy took some pieces of, where what is left is under half that
     * buffer, into buffers of their own, so a buffer kept because it was at least half used does not stay pinned
     * once it no longer is. The buffer the body is reading is left to the check when the body moves on.
     */
    private void freeEroded(CompositeByteBuf target) {
        if (eroded == null || eroded.isEmpty()) {
            return;
        }
        for (int i = 0; i < eroded.size(); i++) {
            ByteBuf root = eroded.get(i);
            Use use = uses.get(root);
            int index = target.numComponents() - 1;
            while (use != null && use.pieces > 0 && root != lastRoot && underHalf(root, use) && index >= merged) {
                if (root(target.internalComponent(index)) == root) {
                    int end = index;
                    while (index > merged && root(target.internalComponent(index - 1)) == root) {
                        index--;
                    }
                    copyIntoOwnBuffer(target, index, end);
                    use = uses.get(root);
                }
                index--;
            }
        }
        eroded.clear();
    }

    private static boolean underHalf(ByteBuf root, Use use) {
        return 2L * use.held < root.capacity();
    }

    private boolean isBlock(ByteBuf root) {
        Use use = uses.get(root);
        return use != null && use.block;
    }

    private Use use(ByteBuf root, boolean block) {
        if (uses == null) {
            uses = new IdentityHashMap<>();
        }
        return uses.computeIfAbsent(root, key -> new Use(block));
    }

    private void track(ByteBuf root, int bytes) {
        if (!tracking) {
            return;
        }
        Use use = use(root, false);
        use.pieces++;
        use.held += bytes;
    }

    private void trackBlock(ByteBuf block, int bytes) {
        if (!tracking) {
            return;
        }
        Use use = use(root(block), true);
        use.pieces++;
        use.held += bytes;
    }

    /**
     * Forgets the pieces of components {@code start} to {@code end}, which are about to be removed or merged.
     */
    private void untrack(CompositeByteBuf target, int start, int end) {
        if (!tracking || uses == null) {
            return;
        }
        for (int i = start; i <= end; i++) {
            ByteBuf component = target.internalComponent(i);
            ByteBuf root = root(component);
            Use use = uses.get(root);
            if (use != null) {
                use.pieces--;
                use.held -= component.readableBytes();
                if (use.pieces == 0) {
                    uses.remove(root);
                    if (root == lastRoot) {
                        lastRoot = null;
                        lastUse = null;
                    }
                } else if (!use.block && root != lastRoot && underHalf(root, use)) {
                    if (eroded == null) {
                        eroded = new ArrayList<>();
                    }
                    eroded.add(root);
                }
            }
        }
    }

    private static ByteBuf root(ByteBuf piece) {
        ByteBuf root = piece;
        for (ByteBuf next = root.unwrap(); next != null && next != root; next = root.unwrap()) {
            root = next;
        }
        return root;
    }

    @Override
    protected void finishAggregation(FullHttpMessage aggregated) throws Exception {
        reset(null);
        requestBeingAggregated = null;
        super.finishAggregation(aggregated);
    }

    /**
     * Where Netty would answer 413 and close a socket at once, with the rest of the body unread, the connection ends
     * with a {@link LingeringClose} instead: closed at once, the kernel resets it and the client may lose the 413.
     * A request whose connection Netty keeps, a response, or a channel that is not a socket (an HTTP/2 stream) is left
     * to Netty.
     */
    @Override
    protected void handleOversizedMessage(ChannelHandlerContext ctx, HttpMessage oversized) throws Exception {
        reset(null);
        requestBeingAggregated = null;
        if (oversized instanceof HttpRequest && ctx.channel() instanceof DuplexChannel && closesAfterTooLarge(ctx, oversized)) {
            Channel channel = ctx.channel();
            ctx.writeAndFlush(TOO_LARGE_CLOSE.retainedDuplicate()).addListener(written -> LingeringClose.close(channel));
            return;
        }
        super.handleOversizedMessage(ctx, oversized);
    }

    /**
     * Netty's condition for closing after a 413: the body has started arriving, so its end cannot be found; reading
     * is paused, so it would never resume; or the request is not keep-alive and is not waiting for a 100 Continue.
     */
    private static boolean closesAfterTooLarge(ChannelHandlerContext ctx, HttpMessage oversized) {
        return oversized instanceof FullHttpMessage
            || !ctx.channel().config().isAutoRead()
            || !HttpUtil.is100ContinueExpected(oversized) && !HttpUtil.isKeepAlive(oversized);
    }

    /**
     * Called straight after Netty appended a piece of {@code length} bytes as the last component of {@code target}.
     */
    private void appended(CompositeByteBuf target, int length) {
        if (length == 0) {
            return;
        }
        if (length >= SMALL_PIECE_BYTES || target.numComponents() <= COALESCE_AFTER_COMPONENTS || !lastComponentHolds(target, length)) {
            endRun();
        } else {
            if (runPieces == 0) {
                runOffset = target.capacity() - length;
            }
            if (++runPieces == RUN_PIECES) {
                copyRunIntoBlocks(target);
            }
        }
        limitComponents(target);
    }

    /**
     * Replaces the run's components with the same bytes copied into the free tail of the current block, and into a
     * new block for what does not fit (a run is under 16 KiB, so at most one). Where the block's last slice is the
     * component just before the run, that slice is extended instead of adding another. Keeping the current block
     * across larger pieces means at most one block per body is part-used.
     */
    private void copyRunIntoBlocks(CompositeByteBuf target) {
        int runStart = target.numComponents() - runPieces;
        runPieces = 0;
        copyIntoBlocks(target, runStart, target.numComponents(), BLOCK_BYTES);
    }

    /**
     * Copies components {@code runStart} to {@code runEnd} (exclusive), starting at byte {@code runOffset}, as
     * {@link #copyRunIntoBlocks} does for a run at the end of the body; a new block is at least {@code newBlockBytes}.
     */
    private void copyIntoBlocks(CompositeByteBuf target, int runStart, int runEnd, int newBlockBytes) {
        if (runStart < 1 || target.toByteIndex(runStart) != runOffset) {
            return;
        }
        int runBytes = (runEnd == target.numComponents() ? target.capacity() : target.toByteIndex(runEnd)) - runOffset;
        boolean intoTail = block != null && blockFill < block.capacity();
        boolean adjacent = intoTail
            && target.toByteIndex(runStart - 1) == sliceOffset
            && sliceOffset + blockFill - sliceStart == runOffset;
        int intoBlock = intoTail ? Math.min(block.capacity() - blockFill, runBytes) : 0;
        // allocate before changing the body, so a failed allocation cannot lose the run
        int nextBytes = Math.max(newBlockBytes, runBytes - intoBlock);
        ByteBuf next = intoBlock < runBytes ? target.alloc().buffer(nextBytes, nextBytes) : null;
        try {
            if (intoBlock > 0) {
                target.getBytes(runOffset, block, blockFill, intoBlock);
            }
            if (next != null) {
                target.getBytes(runOffset + intoBlock, next, 0, runBytes - intoBlock);
            }
            // sliced only once nothing more can throw, so the retained slice is always added
            ByteBuf tailSlice = null;
            if (intoBlock > 0) {
                tailSlice = adjacent
                    ? block.retainedSlice(sliceStart, blockFill + intoBlock - sliceStart)
                    : block.retainedSlice(blockFill, intoBlock);
            }
            int firstReplaced = adjacent ? runStart - 1 : runStart;
            untrack(target, firstReplaced, runEnd - 1);
            target.removeComponents(firstReplaced, runEnd - firstReplaced);
            int index = firstReplaced;
            if (tailSlice != null) {
                if (!adjacent) {
                    sliceStart = blockFill;
                    sliceOffset = runOffset;
                }
                target.addComponent(false, index++, tailSlice);
                trackBlock(block, tailSlice.readableBytes());
                blockFill += intoBlock;
            }
            if (next != null) {
                sliceStart = 0;
                sliceOffset = runOffset + intoBlock;
                target.addComponent(false, index, next.retainedSlice(0, runBytes - intoBlock));
                trackBlock(next, runBytes - intoBlock);
                block = next;
                blockFill = runBytes - intoBlock;
            }
            target.writerIndex(target.capacity());
        } finally {
            if (next != null) {
                next.release();
            }
        }
    }

    /**
     * Whether the last component holds exactly the {@code length} bytes just appended and the buffer is in the state
     * Netty's aggregation leaves it: nothing read, written to capacity. Anything else is left alone.
     */
    private static boolean lastComponentHolds(CompositeByteBuf target, int length) {
        int last = target.numComponents() - 1;
        return target.readerIndex() == 0
            && target.writerIndex() == target.capacity()
            && target.capacity() - target.toByteIndex(last) == length;
    }

    /**
     * Once the body passes the component limit, merges only the components after the leading {@code merged} ones
     * into one: the HTTP/3 accumulator's rule. Without blocks the merged components count as one, as the single
     * component Netty's consolidation leaves does, so each merge is at the append where Netty would copy the whole
     * body, and never copies or holds more than that would; the composite can then hold up to {@code merged - 1}
     * components over the limit.
     */
    private void limitComponents(CompositeByteBuf target) {
        int components = target.numComponents();
        int limit = maxCumulationBufferComponents();
        int counted = copyingRuns ? components : components - Math.max(merged - 1, 0);
        if (counted <= limit) {
            return;
        }
        // every block slice was added since the last merge, so the merge releases the block
        block = null;
        endRun();
        if (merged >= limit / 2) {
            // once half the limit is merged components, start again by merging the whole body into one
            merged = 0;
        }
        int bytes = target.capacity() - target.toByteIndex(merged);
        untrack(target, merged, components - 1);
        target.consolidate(merged, components - merged);
        track(root(target.internalComponent(merged)), bytes);
        merged++;
    }

    private void endRun() {
        runPieces = 0;
    }

    private void reset(CompositeByteBuf target) {
        composite = target;
        block = null;
        endRun();
        merged = 0;
        if (uses != null) {
            uses.clear();
        }
        if (eroded != null) {
            eroded.clear();
        }
        tracking = target != null;
        lastRoot = null;
        lastUse = null;
    }

    /**
     * How many components hold pieces of one buffer, and how many bytes; a block is this aggregator's own.
     */
    private static final class Use {
        private final boolean block;
        private int pieces;
        private long held;

        Use(boolean block) {
            this.block = block;
        }
    }
}
