package kr.junhyung.mcagents.bot;

/** A join that did not finish, carrying how far it got. */
public final class JoinFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final JoinStage stage;

    public JoinFailure(JoinStage stage, String detail) {
        super("%s: %s".formatted(stage.summary(), detail));
        this.stage = stage;
    }

    public JoinStage stage() {
        return stage;
    }
}
