package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.bot.BotLink;
import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.protocol.ProtocolViolation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Exercised over a real loopback socket, because the framing and the timers are the point. */
class BotLinkTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);

    private ServerSocket listener;
    private Socket serverSide;
    private Socket botSide;
    private BotLink link;

    /** Short enough to watch a blob expire, long enough that a test does not race it by accident. */
    private static final long BLOB_TTL_MS = 300;

    @BeforeEach
    void connect() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(10_000);
        botSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        serverSide = listener.accept();
        link = new BotLink(serverSide, mapper, timers, BLOB_TTL_MS);

        Thread.ofVirtual().start(() -> link.pump(new BotLink.Sink() {
            @Override public void event(Messages.Event event) {}
            @Override public void status(Messages.Status status) {}
            @Override public void log(Messages.Log log) {}
        }));
    }

    @AfterEach
    void disconnect() throws IOException {
        link.close();
        botSide.close();
        listener.close();
        timers.shutdownNow();
    }

    private Messages.Call readCall() throws IOException {
        Frame.Json frame = assertInstanceOf(Frame.Json.class, FrameCodec.read(botSide.getInputStream()));
        return assertInstanceOf(Messages.Call.class, mapper.readValue(frame.payload(), Messages.ToBot.class));
    }

    private void reply(Messages.Result result) throws IOException {
        OutputStream out = botSide.getOutputStream();
        FrameCodec.write(out, new Frame.Json(mapper.writeValueAsBytes(result)));
    }

    @Test
    void aCallReachesTheBotAndItsAnswerComesBack() throws Exception {
        CompletableFuture<Messages.Result> answer =
                link.call("get-position", Map.of("x", 1), 5_000);

        Messages.Call sent = readCall();
        assertEquals("get-position", sent.tool());
        assertEquals(5_000, sent.deadlineMs());

        reply(new Messages.Result(sent.id(), true, "Position: (1, 2, 3)", null, null, null, 4));

        assertEquals("Position: (1, 2, 3)", answer.get(3, TimeUnit.SECONDS).text());
    }

    @Test
    void callIdsAreNeverReused() throws Exception {
        link.call("a", Map.of(), 5_000);
        link.call("b", Map.of(), 5_000);

        assertEquals(List.of(1L, 2L), List.of(readCall().id(), readCall().id()));
    }

    /*
    When the bot stops answering the caller must hear that the link is suspect, not that the tool
    refused. The two send whoever is debugging to different places.
    */
    @Test
    void aBotThatNeverAnswersIsReportedAsAStalledLinkRatherThanAFailedTool() throws Exception {
        CompletableFuture<Messages.Result> answer = link.call("dig-block", Map.of(), 50);

        readCall();

        Messages.Result result = answer.get(5, TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertEquals("bot", result.error().errorClass());
        assertEquals("NO_ANSWER", result.error().code());
        assertTrue(result.text().contains("stalled"), result.text());
    }

    /* And it tells the bot to stop, so a half-done dig does not carry on unattended. */
    @Test
    void abandoningACallSendsACancelForIt() throws Exception {
        link.call("dig-block", Map.of(), 50);
        long id = readCall().id();

        Frame.Json frame = assertInstanceOf(Frame.Json.class, FrameCodec.read(botSide.getInputStream()));
        Messages.Cancel cancel = assertInstanceOf(Messages.Cancel.class,
                mapper.readValue(frame.payload(), Messages.ToBot.class));

        assertEquals(id, cancel.id());
        assertEquals("server_timeout", cancel.reason());
    }

    /* A late answer is normal after abandoning, and dropping it is not a breach of anything. */
    @Test
    void anAnswerThatArrivesAfterTheServerGaveUpIsDropped() throws Exception {
        CompletableFuture<Messages.Result> answer = link.call("dig-block", Map.of(), 50);
        long id = readCall().id();

        assertFalse(answer.get(5, TimeUnit.SECONDS).ok());

        link.settle(id, new Messages.Result(id, true, "too late", null, null, null, 9));

        assertEquals("NO_ANSWER", answer.get(1, TimeUnit.SECONDS).error().code());
    }

    @Test
    void everythingInFlightFailsWhenTheLinkGoesAndNothingIsRetried() throws Exception {
        CompletableFuture<Messages.Result> first = link.call("a", Map.of(), 60_000);
        CompletableFuture<Messages.Result> second = link.call("b", Map.of(), 60_000);

        link.close();

        for (CompletableFuture<Messages.Result> answer : List.of(first, second)) {
            Messages.Result result = answer.get(3, TimeUnit.SECONDS);
            assertEquals("bot", result.error().errorClass());
            assertEquals("LINK_LOST", result.error().code());
        }
    }

    /* A sixty second walk is not a dead link, so the heartbeat must not call it one. */
    @Test
    void aLinkWithWorkInFlightIsNotDeclaredDead() throws Exception {
        link.call("move-to-position", Map.of(), 60_000);
        readCall();

        assertFalse(link.looksDead(1));
        assertEquals(1, link.inFlight());
    }

    @Test
    void blobsAreHandedOverOnceAndThenForgotten() throws IOException {
        UUID id = UUID.randomUUID();
        link.rememberBlob(id, new byte[] {1, 2, 3});

        assertEquals(3, link.takeBlob(id).length);
        assertEquals(null, link.takeBlob(id));
    }

    private Messages.ToBot readFromServer() throws IOException {
        Frame.Json frame = assertInstanceOf(Frame.Json.class, FrameCodec.read(botSide.getInputStream()));
        return mapper.readValue(frame.payload(), Messages.ToBot.class);
    }

    /* helloOk says 8 MiB, and a bot sending more is told which limit it broke rather than being read. */
    @Test
    void aBlobOverTheAdvertisedSizeIsAViolationThatClosesTheLink() throws Exception {
        FrameCodec.write(botSide.getOutputStream(), new Frame.Blob(UUID.randomUUID(), new byte[BotLink.BLOB_BYTES + 1]));

        Messages.Fault fault = assertInstanceOf(Messages.Fault.class, readFromServer());

        assertEquals("FRAME_TOO_LARGE", fault.code());
        assertTrue(fault.message().contains(String.valueOf(BotLink.BLOB_BYTES)), fault.message());
        for (int attempt = 0; attempt < 100 && !link.isClosed(); attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(link.isClosed());
    }

    /**
     * Blobs waiting for the result that names them are capped in total, and a result that takes
     * its blob gives the room back. Driven through the public methods rather than the wire, since
     * writing 32 MiB through a loopback socket is the slow part of nothing worth knowing.
     */
    @Test
    void pendingBlobBytesAreCappedAndReleasedWhenAResultTakesThem() throws Exception {
        byte[] quarter = new byte[BotLink.PENDING_BLOB_BYTES / 4];
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        for (UUID id : ids) {
            link.rememberBlob(id, quarter);
        }
        assertEquals(BotLink.PENDING_BLOB_BYTES, link.pendingBlobBytes());
        ProtocolViolation refused = assertThrows(ProtocolViolation.class,
                () -> link.rememberBlob(UUID.randomUUID(), new byte[1]));
        assertEquals(ProtocolViolation.Code.FRAME_TOO_LARGE, refused.code());

        assertEquals(quarter.length, link.takeBlob(ids.getFirst()).length);

        assertEquals(BotLink.PENDING_BLOB_BYTES - quarter.length, link.pendingBlobBytes());
        link.rememberBlob(UUID.randomUUID(), new byte[1]);
    }

    /* A bot that crashed between the blob and the result must not leave the bytes here for the life of the link. */
    @Test
    void aBlobNoResultNamesIsForgottenAfterItsTtl() throws Exception {
        UUID id = UUID.randomUUID();
        link.rememberBlob(id, new byte[] {1, 2, 3});

        for (int attempt = 0; attempt < 100 && link.pendingBlobBytes() > 0; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        assertEquals(0, link.pendingBlobBytes());
        assertEquals(null, link.takeBlob(id));
    }

    /* helloOk says eight, so the ninth is refused here rather than dropped by a bot holding to its word. */
    @Test
    void theNinthCallInFlightIsRefusedWithoutReachingTheBot() throws Exception {
        for (int call = 0; call < BotLink.IN_FLIGHT_CALLS; call++) {
            link.call("wait-ticks", Map.of(), 60_000);
            readCall();
        }

        Messages.Result refused = link.call("wait-ticks", Map.of(), 60_000).get(1, TimeUnit.SECONDS);

        assertFalse(refused.ok());
        assertEquals("bot", refused.error().errorClass());
        assertEquals("TOO_MANY_IN_FLIGHT", refused.error().code());
        assertEquals(BotLink.IN_FLIGHT_CALLS, link.inFlight());
    }
}
