package top.hcode.hoj.manager.oj;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SiteRatingCalculatorTest {
    @Test void equalOpponentsGainForWinningAndLoseForLosing() {
        Map<String, Integer> delta = SiteRatingCalculator.contest(Arrays.asList(player("a", 1400, 1), player("b", 1400, 2)));
        assertTrue(delta.get("a") > 0); assertTrue(delta.get("b") < 0);
        assertTrue(delta.get("a") + delta.get("b") <= 0);
    }
    @Test void tiesDoNotDependOnUserNameOrInputOrder() {
        List<SiteRatingCalculator.Player> players = Arrays.asList(player("a", 1400, 1.5), player("b", 1400, 1.5), player("c", 1400, 3));
        Map<String, Integer> first = SiteRatingCalculator.contest(players);
        assertEquals(first.get("a"), first.get("b"));
        List<SiteRatingCalculator.Player> reverse = new ArrayList<>(players); Collections.reverse(reverse);
        assertEquals(first, SiteRatingCalculator.contest(reverse));
    }
    @Test void beatingStrongerOpponentsProducesALargerChange() {
        int ordinary = SiteRatingCalculator.contest(Arrays.asList(player("a", 1400, 1), player("b", 1400, 2))).get("a");
        int upset = SiteRatingCalculator.contest(Arrays.asList(player("a", 1400, 1), player("b", 2000, 2))).get("a");
        assertTrue(upset > ordinary);
        assertTrue(SiteRatingCalculator.contest(Collections.singletonList(player("a", 1400, 1))).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SiteRatingCalculator.contest(Arrays.asList(player("a", 1400, 1), player("a", 1400, 2))));
    }
    private SiteRatingCalculator.Player player(String uid, int rating, double place) { return new SiteRatingCalculator.Player(uid, rating, place); }
}
