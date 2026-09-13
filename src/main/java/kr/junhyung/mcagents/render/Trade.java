package kr.junhyung.mcagents.render;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One trade a merchant offers, as read-trades lists it and select-trade answers with it.
 *
 * <p>{@code costA} is the price with demand and reputation applied, which is what the payment slot
 * takes; {@code baseCountA} is what the trade asks before either. A line that showed only one of
 * them could not say whether five emeralds was a discount or simply the price.
 */
public record Trade(int number, Held costA, int baseCountA, Held costB, Held result,
    List<Enchantment> enchantments, int uses, int maxUses, boolean outOfStock, int xp) {

    public record Enchantment(String name, int level) {}

    /** Without its number, which each renderer puts where its own sentence wants it. */
    public static String describe(Trade trade) {
        String price = Held.describe(trade.costA());

        if (trade.costA() != null && trade.costA().count() != trade.baseCountA()) {
            price += " (base " + trade.baseCountA() + ")";
        }
        if (trade.costB() != null) {
            price += " + " + Held.describe(trade.costB());
        }

        String gives = Held.describe(trade.result());
        List<Enchantment> enchantments = trade.enchantments() == null ? List.of() : trade.enchantments();

        if (!enchantments.isEmpty()) {
            gives += " with " + enchantments.stream()
                .map(enchantment -> enchantment.name() + " " + enchantment.level())
                .collect(Collectors.joining(", "));
        }

        String stock = trade.uses() + "/" + trade.maxUses() + " uses" + (trade.outOfStock() ? ", out of stock" : "");

        return price + " -> " + gives + " (" + stock + ")";
    }
}
