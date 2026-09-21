package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The half of both region tools that happens before a bot is touched: the box, and the one command
 * an operation becomes.
 *
 * <p>Every case here is a refusal an agent can act on. What each replaces is a round trip that ends
 * in WorldEdit's usage line, or in a bot asked to read a region somebody mistyped by a thousand,
 * and neither of those says which argument was wrong.
 */
class RegionToolsTest {

    private static Map<String, Object> corner(int x, int y, int z) {
        Map<String, Object> corner = new LinkedHashMap<>();
        corner.put("x", x);
        corner.put("y", y);
        corner.put("z", z);
        return corner;
    }

    private static Map<String, Object> box(Map<String, Object> from, Map<String, Object> to) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("from", from);
        arguments.put("to", to);
        return arguments;
    }

    private static String refusalOf(Executable call) {
        return assertThrows(IllegalArgumentException.class, call).getMessage();
    }

    /**
     * The bound no schema can state. Each axis is capped as well as the product, because 64 x 64 x 8
     * and 512 x 8 x 8 hold the same number of blocks and only one of them is a shape worth reading
     * back a layer at a time.
     */
    @Test
    void aBoxBiggerThanACallTakesIsRefusedByWhicheverLimitItPassed() {
        String longAxis = refusalOf(() ->
                Region.of("read-region", box(corner(0, 64, 0), corner(0, 64, 200))));
        String tooMany = refusalOf(() ->
                Region.of("read-region", box(corner(0, 0, 0), corner(63, 63, 63))));

        assertTrue(longAxis.contains("1 x 1 x 201") && longAxis.contains("no axis may be more than 64"), longAxis);
        assertTrue(tooMany.contains("262144 blocks") && tooMany.contains("the most it takes is 32768"), tooMany);
    }

    /**
     * A box is the same box whichever corner came first, and the bot reports it normalised, so the
     * selection the server sets has to be normalised the same way or the answer would name a
     * different box from the one that was edited.
     */
    @Test
    void theCornersAreSettledIntoALowerAndAnUpperOneWhicheverWayRoundTheyCame() {
        Region given = Region.of("build-region", box(corner(14, 66, 20), corner(10, 64, 18)));

        assertEquals("10,64,18", given.lowerCorner());
        assertEquals("14,66,20", given.upperCorner());
        assertEquals("(10, 64, 18) to (14, 66, 20), 5 x 3 x 3, 45 blocks", given.toString());
    }

    /** A fraction is a coordinate the DTO cannot carry, and flooring it here would move the corner. */
    @Test
    void aFractionalCornerIsRefusedRatherThanFloored() {
        Map<String, Object> half = corner(0, 64, 0);
        half.put("y", 64.5);

        assertTrue(refusalOf(() -> Region.of("read-region", box(half, corner(1, 65, 1))))
                .contains("from.y is 64.5, and a block coordinate is a whole number"));
    }

    @Test
    void anOperationBecomesTheOneCommandWorldEditTakes() {
        assertEquals("//set stone", operation("set", "stone", null));
        assertEquals("//walls 50%stone,50%cobblestone", operation("walls", "50%stone,50%cobblestone", null));
        assertEquals("//replace oak_planks", operation("replace", "oak_planks", null));
        assertEquals("//replace #existing oak_planks", operation("replace", "oak_planks", "#existing"));
        assertEquals("//naturalize", operation("naturalize", null, null));
        assertEquals("//hollow", operation("hollow", null, null));
    }

    /** //hollow reads its first argument as a thickness, so a bare pattern would be refused as one. */
    @Test
    void aHollowWithAPatternKeepsTheThicknessInFrontOfIt() {
        assertEquals("//hollow 0 glass", operation("hollow", "glass", null));
    }

    @Test
    void argumentsThatCannotMakeACommandAreRefusedByName() {
        assertTrue(refusalOf(() -> operation("set", null, null)).contains("needs a pattern"));
        assertTrue(refusalOf(() -> operation("naturalize", "stone", null)).contains("takes no pattern"));
        assertTrue(refusalOf(() -> operation("set", "stone", "#existing")).contains("only \"replace\" takes a mask"));
        assertTrue(refusalOf(() -> operation("carve", "stone", null)).contains("not an operation build-region runs"));
    }

    /**
     * A pattern is one argument of a command, and a space in it makes two: "50% stone" reaches
     * WorldEdit as a pattern of "50%" and a stray word, answered with a usage line an agent reads
     * as its own syntax being wrong rather than as the argument it passed.
     *
     * <p>A leading dash is the same thing one position along. The mask is composed in front of the
     * pattern, so "-f" as a mask lands where //replace reads its flags.
     */
    @Test
    void aPatternThatWouldBreakTheCommandApartIsRefused() {
        assertTrue(refusalOf(() -> operation("set", "50% stone", null)).contains("may not hold a space"));
        assertTrue(refusalOf(() -> operation("replace", "stone", "oak planks")).contains("may not hold a space"));
        assertTrue(refusalOf(() -> operation("set", "/kill", null))
                .contains("would reach the server as a command of its own"));
        assertTrue(refusalOf(() -> operation("replace", "stone", "-f"))
                .contains("would reach WorldEdit as a flag rather than as a mask"));
    }

    private static String operation(String name, String pattern, String mask) {
        Map<String, Object> arguments = box(corner(0, 64, 0), corner(4, 64, 4));
        arguments.put("operation", name);

        if (pattern != null) {
            arguments.put("pattern", pattern);
        }
        if (mask != null) {
            arguments.put("mask", mask);
        }
        return RegionTools.operationCommand(arguments);
    }
}
