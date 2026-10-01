package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;

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
 * A piece is usually a slice of a socket read, which stays allocated while any slice of it is held, so a block copy
 * frees nothing while larger pieces from the same reads are kept: blocks can hold about the body again on top of the
 * reads, which is why the HTTP/1.1-limit aggregators, which rarely reach their limit, do without them.
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
     * rather than the whole body, and copy nothing into blocks. Must be called before the aggregator is added to a
     * pipeline.
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
        if (!coalescing || !(content instanceof CompositeByteBuf) || ((CompositeByteBuf) content).numComponents() != 0) {
            return super.beginAggregation(start, content);
        }
        CompositeByteBuf unlimited = content.alloc().compositeBuffer(Integer.MAX_VALUE);
        content.release();
        try {
            FullHttpMessage aggregated = super.beginAggregation(start, unlimited);
            reset(unlimited);
            return aggregated;
        } catch (Exception | Error e) {
            unlimited.release();
            throw e;
        }
    }

    @Override
    protected void aggregate(FullHttpMessage aggregated, HttpContent content) throws Exception {
        super.aggregate(aggregated, content);
        CompositeByteBuf target = composite;
        if (target == null) {
            return;
        }
        if (!copyingRuns) {
            if (target.numComponents() > maxCumulationBufferComponents() && aggregated.content() == target) {
                limitComponents(target);
            }
        } else if (aggregated.content() == target) {
            appended(target, content.content().readableBytes());
        }
    }

    @Override
    protected void finishAggregation(FullHttpMessage aggregated) throws Exception {
        reset(null);
        super.finishAggregation(aggregated);
    }

    @Override
    protected void handleOversizedMessage(ChannelHandlerContext ctx, HttpMessage oversized) throws Exception {
        reset(null);
        super.handleOversizedMessage(ctx, oversized);
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
        int runBytes = target.capacity() - runOffset;
        runPieces = 0;
        if (runStart < 1 || target.toByteIndex(runStart) != runOffset) {
            return;
        }
        boolean intoTail = block != null && blockFill < block.capacity();
        boolean adjacent = intoTail
            && target.toByteIndex(runStart - 1) == sliceOffset
            && sliceOffset + blockFill - sliceStart == runOffset;
        int intoBlock = intoTail ? Math.min(block.capacity() - blockFill, runBytes) : 0;
        // allocate before changing the body, so a failed allocation cannot lose the run
        ByteBuf next = intoBlock < runBytes ? target.alloc().buffer(BLOCK_BYTES, BLOCK_BYTES) : null;
        try {
            ByteBuf tailSlice = null;
            if (intoBlock > 0) {
                target.getBytes(runOffset, block, blockFill, intoBlock);
                tailSlice = adjacent
                    ? block.retainedSlice(sliceStart, blockFill + intoBlock - sliceStart)
                    : block.retainedSlice(blockFill, intoBlock);
            }
            if (next != null) {
                target.getBytes(runOffset + intoBlock, next, 0, runBytes - intoBlock);
            }
            int firstReplaced = adjacent ? runStart - 1 : runStart;
            target.removeComponents(firstReplaced, target.numComponents() - firstReplaced);
            target.writerIndex(target.capacity());
            if (tailSlice != null) {
                if (!adjacent) {
                    sliceStart = blockFill;
                    sliceOffset = target.capacity();
                }
                target.addComponent(true, tailSlice);
                blockFill += intoBlock;
            }
            if (next != null) {
                sliceStart = 0;
                sliceOffset = target.capacity();
                target.addComponent(true, next.retainedSlice(0, runBytes - intoBlock));
                block = next;
                blockFill = runBytes - intoBlock;
            }
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
        target.consolidate(merged, components - merged);
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
    }
}
