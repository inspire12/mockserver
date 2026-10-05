package org.mockserver.exception;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.RedactedThrowable;
import org.mockserver.logging.MockServerLogger;

import javax.net.ssl.SSLException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.exception.ExceptionHandling.MAX_FAULT_MESSAGE_LENGTH;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.handleThrowable;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.swallowThrowable;

public class ExceptionHandlingTest {

    @Test
    public void shouldSwallowException() {
        String originalLogLevel = ConfigurationProperties.logLevel().name();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            ExceptionHandling.mockServerLogger = mock(MockServerLogger.class);

            // when
            swallowThrowable(() -> {
                throw new RuntimeException();
            });

            // then
            verify(ExceptionHandling.mockServerLogger).logEvent(any(LogEntry.class));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel);
        }
    }

    @Test
    public void shouldOnlyLogExceptions() {
        // given
        ExceptionHandling.mockServerLogger = mock(MockServerLogger.class);

        // when
        swallowThrowable(() -> System.out.println("ignore me"));

        // then
        verify(ExceptionHandling.mockServerLogger, never()).logEvent(any(LogEntry.class));
    }

    @Test
    public void shouldIdentifySslExceptionCauseAsSslOrDecoderFault() {
        // a wrapping throwable whose cause is an SSLException is exactly what
        // connectionClosedException returns false for (~line 123-124)
        Throwable wrapped = new RuntimeException("relay failure", new SSLException("handshake failed"));

        // routes to the WARN branch (helper true) and NOT the ERROR branch (connectionClosed false)
        assertThat(isSslOrDecoderFault(wrapped), is(true));
        assertThat(connectionClosedException(wrapped), is(false));
    }

    @Test
    public void shouldIdentifyDecoderExceptionAsSslOrDecoderFault() {
        Throwable throwable = new DecoderException("could not decode");

        assertThat(isSslOrDecoderFault(throwable), is(true));
        assertThat(connectionClosedException(throwable), is(false));
    }

    @Test
    public void shouldIdentifyNotSslRecordExceptionAsSslOrDecoderFault() {
        Throwable throwable = new NotSslRecordException("not an SSL/TLS record");

        assertThat(isSslOrDecoderFault(throwable), is(true));
        assertThat(connectionClosedException(throwable), is(false));
    }

    @Test
    public void shouldNotIdentifyBenignConnectionCloseAsSslOrDecoderFault() {
        // benign close: not an SSL/decoder fault and not an unexpected exception
        Throwable throwable = new RuntimeException("Connection reset by peer");

        assertThat(isSslOrDecoderFault(throwable), is(false));
        assertThat(connectionClosedException(throwable), is(false));
    }

    @Test
    public void shouldNotIdentifyUnexpectedExceptionAsSslOrDecoderFault() {
        // unexpected exception: stays on the ERROR branch (connectionClosed true), not WARN
        Throwable throwable = new IllegalStateException("something unexpected went wrong");

        assertThat(isSslOrDecoderFault(throwable), is(false));
        assertThat(connectionClosedException(throwable), is(true));
    }

    @Test
    public void shouldRecogniseTheDirectMemoryLimitAnywhereInTheCauseChain() throws Exception {
        java.lang.reflect.Constructor<io.netty.util.internal.OutOfDirectMemoryError> constructor =
            io.netty.util.internal.OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Throwable limit = constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory (used: 67108864, max: 67108864)");

        assertThat(directMemoryLimitReached(limit), is(true));
        assertThat(directMemoryLimitReached(new DecoderException(limit)), is(true));
        assertThat(directMemoryLimitReached(new OutOfMemoryError("Java heap space")), is(false));
        assertThat(directMemoryLimitReached(new RuntimeException()), is(false));
        assertThat(ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED, containsString("-XX:MaxDirectMemorySize"));
    }

    @Test
    public void shouldLeaveAFaultsMessageAsItIsUpToTheBound() {
        String atTheBound = "a".repeat(MAX_FAULT_MESSAGE_LENGTH);

        assertThat(boundedFaultMessage(new DecoderException(atTheBound)), is(atTheBound));
        assertThat(boundedFaultMessage(new DecoderException("bad frame")), is("bad frame"));
        assertThat(boundedFaultMessage(new DecoderException((String) null)), is(nullValue()));
    }

    @Test
    public void shouldCutAFaultsMessageThatIsOverTheBound() {
        String message = boundedFaultMessage(new DecoderException("a".repeat(MAX_FAULT_MESSAGE_LENGTH + 1)));

        assertThat(message.length(), is(MAX_FAULT_MESSAGE_LENGTH));
        assertThat(message, startsWith("aaaa"));
        assertThat(message, endsWith("..."));
    }

    @Test
    public void shouldSayHowManyBytesWereNotATlsRecordAndNotWhichBytes() {
        // Netty's JDK TLS handler: a hex dump of every byte read, two characters a byte
        NotSslRecordException shortRead = new NotSslRecordException("not an SSL/TLS record: 474554");
        NotSslRecordException longRead = new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(60_000));

        assertThat(boundedFaultMessage(shortRead), is("not an SSL/TLS record: 3 bytes"));
        assertThat(boundedFaultMessage(longRead), is("not an SSL/TLS record: 60000 bytes"));
        // a decoder's message is its cause, class name first
        assertThat(boundedFaultMessage(new DecoderException(longRead)), is("io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes"));
        // Netty's OpenSSL TLS handler dumps nothing
        assertThat(boundedFaultMessage(new NotSslRecordException("not an SSL/TLS record")), is("not an SSL/TLS record"));
    }

    @Test
    public void shouldReplaceTheDumpThatNettysJdkTlsHandlerWrites() throws Exception {
        byte[] notTls = new byte[2000];
        Arrays.fill(notTls, (byte) 'A');
        SslHandler jdkTls = SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build().newHandler(UnpooledByteBufAllocator.DEFAULT);
        List<Throwable> faults = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(jdkTls, new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                faults.add(cause);
            }
        });

        channel.writeInbound(Unpooled.wrappedBuffer(notTls));

        assertThat(faults, hasSize(1));
        Throwable fault = faults.get(0);
        assertThat(fault, instanceOf(DecoderException.class));
        assertThat("what Netty writes", fault.getMessage(), is("io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: " + "41".repeat(notTls.length)));
        assertThat(boundedFaultMessage(fault), is("io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 2000 bytes"));
        assertThat(rendered(boundedFault(fault)), not(containsString("4141")));
        assertThat(rendered(boundedFault(fault)), containsString("Caused by: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 2000 bytes"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveAMessageItHasAlreadyBoundedAsItIs() {
        // not a dump: what follows the prefix is not hex digits two to a byte
        for (String bounded : new String[]{"not an SSL/TLS record: 2000 bytes", "not an SSL/TLS record: 12 bytes", "not an SSL/TLS record: ", "not an SSL/TLS record: abc", "not an SSL/TLS record: 4G", "not an SSL/TLS record: 4A"}) {
            assertThat(boundedFaultMessage(new NotSslRecordException(bounded)), is(bounded));
        }
        String cut = boundedFaultMessage(new DecoderException("a".repeat(MAX_FAULT_MESSAGE_LENGTH + 1)));
        assertThat(boundedFaultMessage(new DecoderException(cut)), is(cut));
    }

    @Test
    public void shouldAttachACopyItMadeAsItIs() {
        Throwable dump = new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(60_000)));
        // its copy's message is the class name and then 256 characters
        Throwable tooLong = new DecoderException("a".repeat(MAX_FAULT_MESSAGE_LENGTH + 1), new SSLException("b".repeat(MAX_FAULT_MESSAGE_LENGTH + 1)));
        for (Throwable fault : new Throwable[]{dump, tooLong}) {
            Throwable copy = boundedFault(fault);

            assertThat(copy, instanceOf(RedactedThrowable.class));
            assertThat(((RedactedThrowable) copy).getRewrittenMessage(), is(boundedFaultMessage(fault)));
            assertThat(copy.getMessage(), is("io.netty.handler.codec.DecoderException: " + boundedFaultMessage(fault)));
            assertThat(boundedFault(copy), is(sameInstance(copy)));
        }
    }

    @Test
    public void shouldAttachAFaultItselfWhenEveryMessageIsWithinTheBound() {
        Throwable fault = new DecoderException("bad frame", new SSLException("bad record"));
        fault.addSuppressed(new IllegalStateException("also"));

        assertThat(boundedFault(fault), is(sameInstance(fault)));
    }

    @Test
    public void shouldAttachACopyWithTheSameStackTracesWhenAMessageIsNot() {
        NotSslRecordException notTls = new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(60_000));
        Throwable fault = new DecoderException(notTls);

        Throwable attached = boundedFault(fault);

        String rendered = rendered(attached);
        assertThat(rendered, not(containsString("4141")));
        assertThat(rendered, startsWith("io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes"));
        assertThat(rendered, containsString("Caused by: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes"));
        assertThat(attached.getStackTrace(), is(fault.getStackTrace()));
        assertThat(attached.getCause().getStackTrace(), is(notTls.getStackTrace()));
    }

    @Test
    public void shouldBoundTheMessageOfACauseAndOfASuppressedThrowable() {
        String tooLong = "b".repeat(MAX_FAULT_MESSAGE_LENGTH + 1);
        Throwable longCause = new RuntimeException("relay failed", new SSLException(tooLong));
        Throwable longSuppressed = new DecoderException("bad frame");
        longSuppressed.addSuppressed(new IllegalStateException(tooLong));
        // a cause that is its own cause's cause, which must not be followed for ever
        Exception first = new Exception("first");
        Exception second = new Exception("second", first);
        first.initCause(second);

        assertThat(rendered(boundedFault(longCause)), not(containsString(tooLong)));
        assertThat(rendered(boundedFault(longCause)), containsString("Caused by: javax.net.ssl.SSLException: bbbb"));
        assertThat(rendered(boundedFault(longSuppressed)), not(containsString(tooLong)));
        assertThat(rendered(boundedFault(longSuppressed)), containsString("Suppressed: java.lang.IllegalStateException: bbbb"));
        assertThat(boundedFault(first), is(sameInstance(first)));
    }

    @Test
    public void shouldNameWhatWasThrownOnceInACopyOfACopy() {
        Throwable fault = new DecoderException("c".repeat(MAX_FAULT_MESSAGE_LENGTH + 1));

        // a second pass, as redacting a credential from an entry's throwable makes: it is given the message alone
        List<String> rewritten = new ArrayList<>();
        Throwable copyOfACopy = RedactedThrowable.of(boundedFault(fault), message -> {
            rewritten.add(message);
            return message.replace("ccc...", "[cut]");
        });

        assertThat(rewritten, hasSize(1));
        assertThat(rewritten.get(0), is(boundedFaultMessage(fault)));
        assertThat(copyOfACopy.toString(), startsWith("io.netty.handler.codec.DecoderException: cccc"));
        assertThat(copyOfACopy.toString(), endsWith("[cut]"));
        assertThat(((RedactedThrowable) copyOfACopy).getOriginalClassName(), is("io.netty.handler.codec.DecoderException"));
        assertThat(copyOfACopy.getStackTrace(), is(fault.getStackTrace()));
    }

    private static String rendered(Throwable throwable) {
        StringWriter rendered = new StringWriter();
        throwable.printStackTrace(new PrintWriter(rendered));
        return rendered.toString();
    }

}
