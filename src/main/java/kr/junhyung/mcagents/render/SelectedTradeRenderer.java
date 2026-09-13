package kr.junhyung.mcagents.render;

import java.util.List;

/**
 * A trade picked, and what that put in the three trading slots.
 *
 * <p>The slots are the answer. Picking a trade that is out of stock, or one the inventory cannot pay
 * for, is accepted like any other and leaves the result slot empty, and a sentence that only named
 * the trade would read the same for all three.
 */
public final class SelectedTradeRenderer implements Renderer<SelectedTradeRenderer.View> {

    public record View(Trade trade, Held paymentA, Held paymentB, Held result) {}

    @Override
    public String render(View view) {
        String header = "Picked trade " + view.trade().number() + " " + Text.DATA_NOTICE + ": "
            + Trade.describe(view.trade());

        return Text.withLines(header, List.of(
            "  slot 0: " + Held.describe(view.paymentA()),
            "  slot 1: " + Held.describe(view.paymentB()),
            "  slot 2: " + Held.describe(view.result()),
            next(view)));
    }

    private static String next(View view) {
        if (view.result() != null) {
            return "Take slot 2 with click-slot to trade: a plain click trades once, a shift-click as many times as the payment slots cover.";
        }
        if (view.trade().outOfStock()) {
            return "Nothing to take: the trade is out of stock until the villager restocks.";
        }
        return "Nothing to take: the inventory does not hold the price, so the payment slots do not cover it.";
    }
}
