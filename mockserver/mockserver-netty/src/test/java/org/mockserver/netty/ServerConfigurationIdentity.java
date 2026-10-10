package org.mockserver.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds every {@link Configuration} a running {@link MockServer} holds that is NOT the instance
 * {@link LifeCycle#getConfiguration()} returns. A component built on its own instance would never see
 * a runtime {@code PUT /mockserver/configuration} change.
 *
 * <p>It walks the fields of {@code org.mockserver} objects reachable from the server, including the
 * channel initializer behind the {@link ServerBootstrap}, and then the handlers a connection gets: the
 * initializer is run on an {@link EmbeddedChannel} and fed an HTTP/1.1 request line and an h2c
 * preface, so {@code PortUnificationHandler}, the HTTP/1.1 handlers and the HTTP/2 multiplex child
 * initializer (with the request handler it builds) are walked too.
 */
final class ServerConfigurationIdentity {

    // Deliberately separate instances, documented at their declaration.
    private static final Set<String> DELIBERATELY_SEPARATE = Collections.singleton(
        // RequestMatchers: a non-fail-fast copy used only to count match differences
        "nonFailFastMatcherBuilder"
    );

    private static final String HTTP1_REQUEST_LINE = "GET /configuration-identity HTTP/1.1\r\n";
    private static final String H2C_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    private final Configuration expected;
    private final Map<Object, Boolean> visited = new IdentityHashMap<>();
    private final List<String> divergent = new ArrayList<>();
    private final Set<String> matchedHolders = new TreeSet<>();

    private ServerConfigurationIdentity(Configuration expected) {
        this.expected = expected;
    }

    static ServerConfigurationIdentity walk(MockServer server) throws Exception {
        ServerConfigurationIdentity identity = new ServerConfigurationIdentity(server.getConfiguration());
        identity.visit(server, "server");
        ChannelHandler childHandler = ((ServerBootstrap) read(server, LifeCycle.class, "serverServerBootstrap")).config().childHandler();
        identity.visitConnection(childHandler, "http1", HTTP1_REQUEST_LINE);
        identity.visitConnection(childHandler, "h2c", H2C_PREFACE);
        return identity;
    }

    /** Paths whose Configuration is a different instance from the server's. */
    List<String> divergent() {
        return divergent;
    }

    /** {@code HolderClass.field} for every field found holding the server's instance, so a caller can check the walk reached it. */
    Set<String> matchedHolders() {
        return matchedHolders;
    }

    private void visitConnection(ChannelHandler childHandler, String path, String firstBytes) {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.pipeline().addLast(childHandler);
            visitPipeline(channel, path + ".accepted");
            channel.writeInbound(Unpooled.wrappedBuffer(firstBytes.getBytes(StandardCharsets.US_ASCII)));
            visitPipeline(channel, path + ".detected");
            Http2MultiplexHandler multiplexHandler = channel.pipeline().get(Http2MultiplexHandler.class);
            if (multiplexHandler != null) {
                visit(read(multiplexHandler, Http2MultiplexHandler.class, "inboundStreamHandler"), path + ".Http2MultiplexHandler.inboundStreamHandler");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void visitPipeline(EmbeddedChannel channel, String path) {
        for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
            visit(entry.getValue(), path + "[" + entry.getValue().getClass().getSimpleName() + "]");
        }
    }

    private void visit(Object value, String path) {
        if (value == null) {
            return;
        }
        if (value instanceof Configuration) {
            if (value != expected) {
                divergent.add(path);
            }
            return;
        }
        if (!value.getClass().getName().startsWith("org.mockserver.") || visited.put(value, Boolean.TRUE) != null) {
            return;
        }
        for (Class<?> type = value.getClass(); type != null && type.getName().startsWith("org.mockserver."); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || DELIBERATELY_SEPARATE.contains(field.getName())) {
                    continue;
                }
                Object fieldValue;
                try {
                    field.setAccessible(true);
                    fieldValue = field.get(value);
                } catch (RuntimeException | IllegalAccessException e) {
                    continue;
                }
                String fieldPath = path + "." + field.getName();
                if (fieldValue == expected) {
                    matchedHolders.add(type.getSimpleName() + "." + field.getName());
                }
                if (fieldValue instanceof ServerBootstrap) {
                    visit(((ServerBootstrap) fieldValue).config().childHandler(), fieldPath + ".childHandler");
                    visit(((ServerBootstrap) fieldValue).config().handler(), fieldPath + ".handler");
                } else {
                    visit(fieldValue, fieldPath);
                }
            }
        }
    }

    private static Object read(Object target, Class<?> declaringClass, String fieldName) throws ReflectiveOperationException {
        Field field = declaringClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
