package org.igniterealtime.openfire.plugins.kanban.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LexoRankTest {
    @Test
    void producesStableOrderedRanksAtEveryPosition() {
        final LexoRank middle = LexoRank.middle();
        final LexoRank before = LexoRank.between(null, middle);
        final LexoRank after = LexoRank.between(middle, null);
        final LexoRank between = LexoRank.between(middle, after);

        assertTrue(before.compareTo(middle) < 0);
        assertTrue(middle.compareTo(between) < 0);
        assertTrue(between.compareTo(after) < 0);
        assertEquals(middle, LexoRank.parse(middle.toString()));
        assertEquals(middle.hashCode(), LexoRank.parse(middle.toString()).hashCode());
        assertNotEquals(middle, after);
    }

    @Test
    void rejectsMalformedAndUnorderedRanks() {
        assertThrows(NullPointerException.class, () -> LexoRank.parse(null));
        assertThrows(IllegalArgumentException.class, () -> LexoRank.parse("bad"));
        assertThrows(IllegalArgumentException.class, () -> LexoRank.between(LexoRank.middle(), LexoRank.middle()));
        assertThrows(LexoRank.ExhaustedException.class, () -> LexoRank.between(
            LexoRank.parse("0|000000000000:"), LexoRank.parse("0|000000000001:")));
    }

    @Test
    void rebalancesEvenlyIntoTheNextBucket() {
        final List<LexoRank> ranks = LexoRank.rebalance(3, 0);
        assertEquals(3, ranks.size());
        assertEquals(1, ranks.get(0).bucket());
        assertTrue(ranks.get(0).compareTo(ranks.get(1)) < 0);
        assertTrue(ranks.get(1).compareTo(ranks.get(2)) < 0);
        assertEquals(List.of(), LexoRank.rebalance(0, 2));
        assertThrows(IllegalArgumentException.class, () -> LexoRank.rebalance(-1, 0));
    }
}
