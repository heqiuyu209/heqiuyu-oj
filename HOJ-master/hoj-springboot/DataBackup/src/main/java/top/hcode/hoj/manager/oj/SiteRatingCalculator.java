package top.hcode.hoj.manager.oj;

import java.util.*;

/** Station rules v1: practice Elo against a task; CF-style multiplayer contest Elo. */
public final class SiteRatingCalculator {
    public static final int PRACTICE_INITIAL = 800;
    public static final int CONTEST_INITIAL = 1400;
    private SiteRatingCalculator() {}

    public static int practice(int rating, int taskRating) {
        double expected = 1.0 / (1 + Math.pow(10, (taskRating - rating) / 400.0));
        return rating + (int) Math.round(32 * (1 - expected));
    }

    public static final class Player {
        public final String uid;
        public final int rating;
        public final double place;
        public Player(String uid, int rating, double place) { this.uid = uid; this.rating = rating; this.place = place; }
    }

    private static double seed(int rating, Player player, List<Player> players) {
        double seed = 1;
        for (Player other : players) if (!other.uid.equals(player.uid))
            seed += 1.0 / (1 + Math.pow(10, (rating - other.rating) / 400.0));
        return seed;
    }

    public static Map<String, Integer> contest(List<Player> players) {
        Map<String, Integer> delta = new LinkedHashMap<>();
        if (players.size() < 2) return delta;
        Set<String> ids = new HashSet<>();
        for (Player player : players) {
            if (!ids.add(player.uid) || player.place < 1 || player.place > players.size())
                throw new IllegalArgumentException("Invalid contest rating population");
            double target = Math.sqrt(player.place * seed(player.rating, player, players));
            int low = 0, high = 8000;
            while (high - low > 1) {
                int mid = (low + high) / 2;
                if (seed(mid, player, players) < target) high = mid; else low = mid;
            }
            delta.put(player.uid, (low - player.rating) / 2);
        }
        int correction = -delta.values().stream().mapToInt(Integer::intValue).sum() / players.size() - 1;
        delta.replaceAll((uid, value) -> value + correction);
        List<Player> strongest = new ArrayList<>(players);
        strongest.sort(Comparator.comparingInt((Player p) -> p.rating).reversed().thenComparing(p -> p.uid));
        int count = Math.min(players.size(), (int) Math.round(4 * Math.sqrt(players.size())));
        int topSum = strongest.subList(0, count).stream().mapToInt(p -> delta.get(p.uid)).sum();
        int topCorrection = Math.min(0, Math.max(-10, -topSum / count));
        delta.replaceAll((uid, value) -> value + topCorrection);
        return delta;
    }
}
