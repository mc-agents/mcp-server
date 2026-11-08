package kr.junhyung.mcagents.http;

import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Ends every agent's listening stream as the server stops, ahead of Tomcat's drain.
 *
 * <p>The drain waits for every open request, and the GET stream an agent keeps open for the server
 * to push down never ends on its own. One such stream held a rollout for the drain's full thirty
 * seconds with nothing answering, and the agents had given up reconnecting by the time the new pod
 * was up. Closing the transport ends those streams and turns away anything new, while a tool call
 * already running answers on its own request and finishes inside the drain as before.
 */
public class ListeningStreams implements SmartLifecycle {

    private final McpStreamableServerTransportProvider transport;

    private volatile boolean running;

    public ListeningStreams(McpStreamableServerTransportProvider transport) {
        this.transport = transport;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        transport.closeGracefully().block();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops just before the drain, which is the next phase down. */
    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
    }
}
