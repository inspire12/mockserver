package org.mockserver.netty.integration;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

/**
 * What Netty's own loggers report at {@code WARN} or above while a test runs, which is where an exception that
 * MockServer's handlers do not take ends up: outside MockServer's log, its levels and its event log.
 * <p>
 * Create it before the servers under test start and {@link #attach()} it after: threads that exist when it is created
 * are other classes' event loops and are not recorded, nor are those of a group the test names as its own, and
 * MockServer's logging set-up removes the handlers of every logger.
 */
public final class NettyLogCapture implements AutoCloseable {

    public static final String PIPELINE = "io.netty.channel.DefaultChannelPipeline";
    public static final String PROTOCOL_NEGOTIATION = "io.netty.handler.ssl.ApplicationProtocolNegotiationHandler";

    private static final String CAPTURE_CHECK = "capture check";

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Set<Thread> ignoredThreads = ConcurrentHashMap.newKeySet();
    // held here: java.util.logging keeps only a weak reference to a logger, and the handler would go with it
    private final List<Logger> loggers = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue() && !ignoredThreads.contains(Thread.currentThread())) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private final String[] loggerNames;

    public NettyLogCapture(String... loggerNames) {
        this.loggerNames = loggerNames;
        ignoredThreads.addAll(Thread.getAllStackTraces().keySet());
        // the thread that creates the capture is the one that checks it
        ignoredThreads.remove(Thread.currentThread());
    }

    public NettyLogCapture attach() {
        for (String loggerName : loggerNames) {
            Logger logger = Logger.getLogger(loggerName);
            logger.addHandler(handler);
            loggers.add(logger);
        }
        assertThatItSeesWhatNettyLogs();
        return this;
    }

    /**
     * Leaves out what the test's own clients and upstream servers log on their event loops.
     */
    public NettyLogCapture ignoreThreadsOf(EventLoopGroup group) throws Exception {
        for (EventExecutor executor : group) {
            ignoredThreads.add(executor.submit(Thread::currentThread).get(10, TimeUnit.SECONDS));
        }
        return this;
    }

    /**
     * Without this a test would pass for as long as Netty logged somewhere the capture does not look, or once
     * something had taken the capture off its loggers.
     */
    public void assertThatItSeesWhatNettyLogs() {
        List<LogRecord> before = new ArrayList<>(records);
        records.clear();
        try {
            for (Logger logger : loggers) {
                InternalLoggerFactory.getInstance(logger.getName()).warn(CAPTURE_CHECK);
            }
            assertThat(records.stream().map(LogRecord::getLoggerName).collect(Collectors.toList()), contains(loggers.stream().map(Logger::getName).toArray()));
        } finally {
            records.clear();
            records.addAll(before);
        }
    }

    public int size() {
        return records.size();
    }

    /**
     * @return each record after the first {@code from} of them, as its message and what was thrown
     */
    public List<String> since(int from) {
        List<String> all = all();
        return all.subList(Math.min(from, all.size()), all.size());
    }

    public List<String> all() {
        return records.stream().map(record -> record.getMessage() + " " + record.getThrown()).collect(Collectors.toList());
    }

    @Override
    public void close() {
        for (Logger logger : loggers) {
            logger.removeHandler(handler);
        }
    }
}
