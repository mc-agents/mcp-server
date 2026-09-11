package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.bot.BotLink;
import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
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

    @BeforeEach
    void connect() throws IOException {
        listener = new ServerSocket(0);
        listener.setSoTimeout(10_000);
        botSide = new Socket("127.0.0.1", listener.getLocalPort());
        serverSide = listener.accept();
        link = new BotLink(serverSide, mapper, timers);

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
    void blobsAreHandedOverOnceAndThenForgotten() {
        java.util.UUID id = java.util.UUID.randomUUID();
        link.rememberBlob(id, new byte[] {1, 2, 3});

        assertEquals(3, link.takeBlob(id).length);
        assertEquals(null, link.takeBlob(id));
    }
}
