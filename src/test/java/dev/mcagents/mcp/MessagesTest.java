package dev.mcagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.exc.InvalidTypeIdException;
import dev.mcagents.mcp.protocol.Messages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void aHelloRoundTripsWithItsCapabilities() throws Exception {
        String wire = """
            {"t":"hello","protocols":[1],"botName":"alpha","kind":"mineflayer",
             "agentVersion":"0.1.0","mcVersion":"26.1.2","catalogVersion":"1.0.0",
             "capabilities":[{"tool":"dig-block","argsHash":"sha256:abc"}],
             "features":["eventFold"]}
            """;

        Messages.Hello hello = assertInstanceOf(Messages.Hello.class, mapper.readValue(wire, Messages.FromBot.class));

        assertEquals("alpha", hello.botName());
        assertEquals(List.of(1), hello.protocols());
        assertEquals("dig-block", hello.capabilities().getFirst().tool());
    }

    /*
    The failure class is "class" on the wire. Getting this wrong would leave every error
    unclassified, and the class is what decides whether a session stays healthy.
    */
    @Test
    void theFailureClassKeepsItsWireName() throws Exception {
        String wire = """
            {"t":"result","id":7,"ok":false,"text":"no window","elapsedMs":12,
             "error":{"class":"tool","code":"NO_WINDOW_OPEN","message":"nothing is open","retryable":false}}
            """;

        Messages.Result result = assertInstanceOf(Messages.Result.class, mapper.readValue(wire, Messages.FromBot.class));

        assertFalse(result.ok());
        assertEquals("tool", result.error().errorClass());
        assertEquals("NO_WINDOW_OPEN", result.error().code());

        assertTrue(mapper.writeValueAsString(result.error()).contains("\"class\":\"tool\""));
    }

    /* A newer bot may add a field. That must not close the link. */
    @Test
    void anUnknownFieldIsIgnoredSoAnOlderServerKeepsWorking() throws Exception {
        String wire = """
            {"t":"pong","nonce":3,"ts":1000,"busy":0,"somethingNewer":true}
            """;

        assertEquals(3, assertInstanceOf(Messages.Pong.class, mapper.readValue(wire, Messages.FromBot.class)).nonce());
    }

    /* An unknown message is not, because acting on what you cannot read is worse than closing. */
    @Test
    void anUnknownMessageTypeIsRefused() {
        assertThrows(InvalidTypeIdException.class,
                () -> mapper.readValue("{\"t\":\"whatIsThis\"}", Messages.FromBot.class));
    }

    @Test
    void anEventCarriesItsSegmentsApart() throws Exception {
        String wire = """
            {"t":"event","seq":4,"kind":"actionBar","source":"actionbar","text":"ignored",
             "segments":[{"text":"20/20","font":"server:hud/bars_text"},{"text":"0","font":"server:hud/money_text"}],
             "ts":1000,"firstTs":900,"repeats":3,"closed":false}
            """;

        Messages.Event event = assertInstanceOf(Messages.Event.class, mapper.readValue(wire, Messages.FromBot.class));

        assertEquals(2, event.segments().size());
        assertEquals("server:hud/bars_text", event.segments().getFirst().font());
        assertEquals(3, event.repeats());
    }

    @Test
    void aCallSerialisesWithoutTheFieldsItDoesNotHave() throws Exception {
        String json = mapper.writeValueAsString(
                new Messages.Call(1, "get-position", Map.of(), 5000, null));

        assertTrue(json.contains("\"tool\":\"get-position\""));
        assertFalse(json.contains("traceId"));
    }
}
