/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.exceptions.AeronException;
import io.aeron.status.PublicationErrorFrame;
import io.aeron.test.EventLogExtension;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SlowTest;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.Tests;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.aeron.driver.status.SystemCounterDescriptor.ERRORS;
import static io.aeron.driver.status.SystemCounterDescriptor.ERROR_FRAMES_RECEIVED;
import static io.aeron.driver.status.SystemCounterDescriptor.ERROR_FRAMES_SENT;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith({ EventLogExtension.class, InterruptingTestCallback.class })
public class RejectImageTest
{
    public static final long A_VALUE_THAT_SHOWS_WE_ARENT_SPAMMING_ERROR_MESSAGES = 1000L;

    @RegisterExtension
    final SystemTestWatcher systemTestWatcher = new SystemTestWatcher();

    private final String channel = "aeron:udp?endpoint=localhost:10000";
    private final int streamId = 10000;
    private final DirectBuffer message = new UnsafeBuffer("this is a test message".getBytes(US_ASCII));

    private final MediaDriver.Context context = new MediaDriver.Context()
        .dirDeleteOnStart(true)
        .threadingMode(ThreadingMode.SHARED);
    private TestMediaDriver driver;

    @AfterEach
    void tearDown()
    {
        CloseHelper.quietClose(driver);
    }

    private TestMediaDriver launch()
    {
        driver = TestMediaDriver.launch(context, systemTestWatcher);
        return driver;
    }

    private static final class QueuedErrorFrameHandler implements PublicationErrorFrameHandler
    {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final OneToOneConcurrentArrayQueue<PublicationErrorFrame> errorFrameQueue =
            new OneToOneConcurrentArrayQueue<>(512);

        public void onPublicationError(final PublicationErrorFrame errorFrame)
        {
            if (!errorFrameQueue.offer(errorFrame.clone()))
            {
                counter.incrementAndGet();
            }
        }

        PublicationErrorFrame poll()
        {
            if (counter.get() > 0)
            {
                throw new RuntimeException("Failed to offer to the errorFrameQueue in the test");
            }

            return errorFrameQueue.poll();
        }
    }

    @Test
    @InterruptAfter(10)
    @SlowTest
    @SuppressWarnings("MethodLength")
    void shouldRejectSubscriptionsImage() throws IOException
    {
        context.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(1234));

        final TestMediaDriver driver = launch();
        final QueuedErrorFrameHandler errorFrameHandler = new QueuedErrorFrameHandler();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler);

        final AtomicInteger imageAvailable = new AtomicInteger();
        final AtomicInteger imageUnavailable = new AtomicInteger();

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = aeron.addPublication(channel, streamId);
            Subscription sub = aeron.addSubscription(
                channel,
                streamId,
                image -> imageAvailable.getAndIncrement(),
                image -> imageUnavailable.getAndIncrement()))
        {
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            final CountersReader countersReader = aeron.countersReader();
            final long initialErrorFramesReceived = countersReader
                .getCounterValue(ERROR_FRAMES_RECEIVED.id());
            final long initialErrorFramesSent = countersReader
                .getCounterValue(ERROR_FRAMES_SENT.id());
            final long initialErrors = countersReader
                .getCounterValue(ERRORS.id());

            while (pub.offer(message) < 0)
            {
                Tests.yield();
            }

            while (0 == sub.poll((buffer, offset, length, header) -> {}, 1))
            {
                Tests.yield();
            }

            final Image image = sub.imageAtIndex(0);
            assertEquals(pub.position(), image.position());

            final String reason = "Needs to be closed";
            image.reject(reason);

            final long t0 = System.nanoTime();
            while (pub.isConnected())
            {
                Tests.yield();
            }
            final long t1 = System.nanoTime();
            final long value = driver.context().publicationConnectionTimeoutNs();
            assertThat(t1 - t0, lessThan(value));

            while (initialErrorFramesReceived == countersReader.getCounterValue(ERROR_FRAMES_RECEIVED.id()) ||
                initialErrorFramesSent == countersReader.getCounterValue(ERROR_FRAMES_SENT.id()))
            {
                Tests.yield();
            }

            while (initialErrors == countersReader
                .getCounterValue(ERRORS.id()))
            {
                Tests.yield();
            }

            PublicationErrorFrame errorFrame;
            while (null == (errorFrame = errorFrameHandler.poll()))
            {
                Tests.yield();
            }

            assertEquals(reason, errorFrame.errorMessage());
            assertEquals(pub.registrationId(), errorFrame.registrationId());

            while (0 == imageUnavailable.get())
            {
                Tests.yield();
            }

            assertThat(
                countersReader.getCounterValue(ERROR_FRAMES_RECEIVED.id()) - initialErrorFramesReceived,
                lessThan(A_VALUE_THAT_SHOWS_WE_ARENT_SPAMMING_ERROR_MESSAGES));

            assertEquals(1, countersReader.getCounterValue(ERRORS.id()) - initialErrors);

            SystemTests.waitForErrorToOccur(driver.aeronDirectoryName(), containsString(reason), Tests.SLEEP_1_MS);

            // Should reconnect after an image liveness timeout
            while (1 == imageAvailable.get())
            {
                Tests.yield();
            }
            assertTrue(pub.isConnected());
            assertTrue(sub.isConnected());
            assertEquals(1, sub.imageCount());
            assertNotSame(image, sub.imageAtIndex(0));
            assertNotEquals(image.correlationId(), sub.imageAtIndex(0).correlationId());
        }
    }

    @Test
    @InterruptAfter(10)
    @SlowTest
    void shouldOnlyReceivePublicationErrorFrameOnRelevantClient() throws IOException
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));

        final TestMediaDriver driver = launch();
        final QueuedErrorFrameHandler errorFrameHandler1 = new QueuedErrorFrameHandler();
        final QueuedErrorFrameHandler errorFrameHandler2 = new QueuedErrorFrameHandler();

        final Aeron.Context ctx1 = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler1);

        final Aeron.Context ctx2 = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler2);

        try (Aeron aeron1 = Aeron.connect(ctx1);
            Aeron aeron2 = Aeron.connect(ctx2);
            Publication pub = aeron1.addPublication(channel, streamId);
            Subscription sub = aeron1.addSubscription(channel, streamId))
        {
            assertNotNull(aeron2);
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            while (pub.offer(message) < 0)
            {
                Tests.yield();
            }

            while (0 == sub.poll((buffer, offset, length, header) -> {}, 1))
            {
                Tests.yield();
            }

            final Image image = sub.imageAtIndex(0);
            final String reason = "Needs to be closed";
            image.reject(reason);

            PublicationErrorFrame errorFrame;
            while (null == (errorFrame = errorFrameHandler1.poll()))
            {
                Tests.yield();
            }

            assertEquals(pub.registrationId(), errorFrame.registrationId());
            assertEquals(ErrorCode.IMAGE_REJECTED, ErrorCode.get(errorFrame.errorCode()));
            assertEquals(reason, errorFrame.errorMessage());

            final long deadlineMs = System.currentTimeMillis() + 1_000;
            while (System.currentTimeMillis() < deadlineMs)
            {
                assertNull(errorFrameHandler2.poll(), "Aeron client without publication should not report error");
                Tests.yield();
            }
        }
    }

    @Test
    @InterruptAfter(10)
    @SlowTest
    void shouldReceivePublicationErrorFramesAllRelevantClients() throws IOException
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));

        final TestMediaDriver driver = launch();
        final QueuedErrorFrameHandler errorFrameHandler1 = new QueuedErrorFrameHandler();
        final QueuedErrorFrameHandler errorFrameHandler2 = new QueuedErrorFrameHandler();

        final Aeron.Context ctx1 = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler1);

        final Aeron.Context ctx2 = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler2);

        try (Aeron aeron1 = Aeron.connect(ctx1);
            Aeron aeron2 = Aeron.connect(ctx2);
            Publication pub = aeron1.addPublication(channel, streamId);
            Publication pubOther = aeron2.addPublication(channel, streamId);
            Subscription sub = aeron1.addSubscription(channel, streamId))
        {
            assertNotNull(aeron2);
            Tests.awaitConnected(pub);
            Tests.awaitConnected(pubOther);
            Tests.awaitConnected(sub);

            while (pub.offer(message) < 0)
            {
                Tests.yield();
            }

            while (0 == sub.poll((buffer, offset, length, header) -> {}, 1))
            {
                Tests.yield();
            }

            final Image image = sub.imageAtIndex(0);
            final String reason = "Needs to be closed";
            image.reject(reason);

            PublicationErrorFrame errorFrame;
            while (null == (errorFrame = errorFrameHandler1.poll()))
            {
                Tests.yield();
            }

            assertEquals(pub.registrationId(), errorFrame.registrationId());
            assertEquals(ErrorCode.IMAGE_REJECTED, ErrorCode.get(errorFrame.errorCode()));
            assertEquals(reason, errorFrame.errorMessage());

            while (null == (errorFrame = errorFrameHandler2.poll()))
            {
                Tests.yield();
            }

            assertEquals(pub.registrationId(), errorFrame.registrationId());
            assertEquals(ErrorCode.IMAGE_REJECTED, ErrorCode.get(errorFrame.errorCode()));
            assertEquals(reason, errorFrame.errorMessage());
        }
    }

    @Test
    @InterruptAfter(10)
    @SlowTest
    void shouldRejectSubscriptionsImageManualMdc()
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));

        final QueuedErrorFrameHandler errorFrameHandler = new QueuedErrorFrameHandler();
        final TestMediaDriver driver = launch();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler);

        final AtomicInteger imageAvailable = new AtomicInteger(0);
        final AtomicInteger imageUnavailable = new AtomicInteger(0);
        final String mdc = "aeron:udp?control-mode=manual";
        final String channel = "aeron:udp?endpoint=localhost:10000";

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = aeron.addPublication(mdc, streamId);
            Subscription sub = aeron.addSubscription(
                channel,
                streamId,
                (image) -> imageAvailable.incrementAndGet(),
                (image) -> imageUnavailable.incrementAndGet()))
        {
            final long destinationRegistrationId = pub.asyncAddDestination(channel);

            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            final long initialErrorMessagesReceived = aeron.countersReader()
                .getCounterValue(ERROR_FRAMES_RECEIVED.id());

            while (pub.offer(message) < 0)
            {
                Tests.yield();
            }

            while (0 == sub.poll((buffer, offset, length, header) -> {}, 1))
            {
                Tests.yield();
            }

            final Image image = sub.imageAtIndex(0);
            assertEquals(pub.position(), image.position());

            final int initialAvailable = imageAvailable.get();
            final String reason = "Needs to be closed";
            image.reject(reason);

            final long t0 = System.nanoTime();
            while (pub.isConnected())
            {
                Tests.yield();
            }
            final long t1 = System.nanoTime();
            final long value = driver.context().publicationConnectionTimeoutNs();
            assertThat(t1 - t0, lessThan(value));

            while (initialErrorMessagesReceived == aeron.countersReader()
                .getCounterValue(ERROR_FRAMES_RECEIVED.id()))
            {
                Tests.yield();
            }

            while (0 == imageUnavailable.get())
            {
                Tests.yield();
            }

            assertThat(
                aeron.countersReader().getCounterValue(ERROR_FRAMES_RECEIVED.id()),
                lessThan(A_VALUE_THAT_SHOWS_WE_ARENT_SPAMMING_ERROR_MESSAGES));

            while (initialAvailable == imageAvailable.get())
            {
                Tests.yield();
            }

            PublicationErrorFrame errorFrame;
            while (null == (errorFrame = errorFrameHandler.poll()))
            {
                Tests.yield();
            }

            assertEquals(reason, errorFrame.errorMessage());
            assertEquals(pub.registrationId(), errorFrame.registrationId());
            assertEquals(destinationRegistrationId, errorFrame.destinationRegistrationId());
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorIfRejectionReasonIsTooLong()
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));
        final byte[] bytes = new byte[1024];
        Arrays.fill(bytes, (byte)'x');
        final String tooLongReason = new String(bytes, US_ASCII);

        final TestMediaDriver driver = launch();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName());

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = aeron.addPublication(channel, streamId);
            Subscription sub = aeron.addSubscription(channel, streamId))
        {
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            assertThrows(AeronException.class, () -> sub.imageAtIndex(0).reject(tooLongReason));
        }
    }


    @Test
    @InterruptAfter(10)
    void shouldErrorIfRejectionReasonIsTooLongForLocalBuffer()
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));
        final byte[] bytes = new byte[1024 * 1024];
        Arrays.fill(bytes, (byte)'x');
        final String tooLongReason = new String(bytes, US_ASCII);

        final TestMediaDriver driver = launch();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName());

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = aeron.addPublication(channel, streamId);
            Subscription sub = aeron.addSubscription(channel, streamId))
        {
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            assertThrows(IllegalArgumentException.class, () -> sub.imageAtIndex(0).reject(tooLongReason));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorIfUsingAndIpcChannel()
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));
        final String rejectionReason = "Reject this";

        final TestMediaDriver driver = launch();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName());

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = aeron.addPublication(CommonContext.IPC_CHANNEL, streamId);
            Subscription sub = aeron.addSubscription(CommonContext.IPC_CHANNEL, streamId))
        {
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            final AeronException ex = assertThrows(AeronException.class, () -> sub.imageAtIndex(0)
                .reject(rejectionReason));
            assertThat(ex.getMessage(), containsString("Unable to resolve image for correlationId"));
        }
    }


    @ParameterizedTest
    @ValueSource(strings = { "127.0.0.1", "[::1]" })
    @InterruptAfter(5)
    void shouldReturnAllParametersToApi(final String addressStr) throws UnknownHostException
    {
        final InetAddress address = InetAddress.getByName(addressStr);
        assumeTrue(address instanceof Inet4Address || System.getProperty("java.net.preferIPv4Stack") == null);

        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));

        final TestMediaDriver driver = launch();
        final QueuedErrorFrameHandler errorFrameHandler = new QueuedErrorFrameHandler();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler);

        final long groupTag = 1001;
        final int port = 10001;

        final String mdc = "aeron:udp?control-mode=dynamic|control=" + addressStr + ":10000|fc=tagged,g:" + groupTag;
        final String channel =
            "aeron:udp?control=" + addressStr + ":10000|endpoint=" + addressStr + ":" + port + "|gtag=" + groupTag;

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = aeron.addPublication(mdc, streamId);
            Subscription sub = aeron.addSubscription(channel, streamId))
        {
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            while (pub.offer(message) < 0)
            {
                Tests.yield();
            }

            while (0 == sub.poll((buffer, offset, length, header) -> {}, 1))
            {
                Tests.yield();
            }

            final Image image = sub.imageAtIndex(0);
            assertEquals(pub.position(), image.position());

            final String reason = "Needs to be closed";
            image.reject(reason);

            PublicationErrorFrame errorFrame;
            while (null == (errorFrame = errorFrameHandler.poll()))
            {
                Tests.yield();
            }

            assertEquals(reason, errorFrame.errorMessage());
            assertEquals(pub.registrationId(), errorFrame.registrationId());
            assertEquals(Aeron.NULL_VALUE, errorFrame.destinationRegistrationId());
            assertEquals(pub.streamId(), errorFrame.streamId());
            assertEquals(pub.sessionId(), errorFrame.sessionId());
            assertEquals(groupTag, errorFrame.groupTag());
            assertNotNull(errorFrame.sourceAddress());
            assertEquals(new InetSocketAddress(addressStr, port), errorFrame.sourceAddress());
        }
    }

    @ParameterizedTest
    @InterruptAfter(5)
    @ValueSource(booleans = { true, false })
    void shouldOnlyReceivePublicationErrorFrames(final boolean isExclusive) throws IOException
    {
        context.imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(3));

        final TestMediaDriver driver = launch();
        final QueuedErrorFrameHandler errorFrameHandler = new QueuedErrorFrameHandler();

        final Aeron.Context ctx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .publicationErrorFrameHandler(errorFrameHandler);

        final Function<Aeron, ? extends Publication> addPub = (aeron) -> isExclusive ?
            aeron.addExclusivePublication(channel, streamId) : aeron.addPublication(channel, streamId);

        try (Aeron aeron = Aeron.connect(ctx);
            Publication pub = addPub.apply(aeron);
            Subscription sub = aeron.addSubscription(channel, streamId))
        {
            Tests.awaitConnected(pub);
            Tests.awaitConnected(sub);

            while (pub.offer(message) < 0)
            {
                Tests.yield();
            }

            while (0 == sub.poll((buffer, offset, length, header) -> {}, 1))
            {
                Tests.yield();
            }

            final Image image = sub.imageAtIndex(0);
            final String reason = "Needs to be closed";
            image.reject(reason);

            while (null == errorFrameHandler.poll())
            {
                Tests.yield();
            }
        }
    }
}
