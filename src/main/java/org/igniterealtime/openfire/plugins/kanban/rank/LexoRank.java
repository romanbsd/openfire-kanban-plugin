package org.igniterealtime.openfire.plugins.kanban.rank;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compact, fixed-width LexoRank keys with rotating rebalance buckets. */
public final class LexoRank implements Comparable<LexoRank> {
    private static final int WIDTH = 12;
    private static final BigInteger BASE = BigInteger.valueOf(36);
    private static final BigInteger LIMIT = BASE.pow(WIDTH).subtract(BigInteger.ONE);
    private static final Pattern FORMAT = Pattern.compile("([0-2])\\|([0-9a-z]{12}):");

    private final int bucket;
    private final BigInteger value;

    private LexoRank(int bucket, BigInteger value) {
        if (bucket < 0 || bucket > 2 || value.signum() < 0 || value.compareTo(LIMIT) > 0) {
            throw new IllegalArgumentException("LexoRank is outside its supported range");
        }
        this.bucket = bucket;
        this.value = value;
    }

    public static LexoRank parse(String encoded) {
        final Matcher matcher = FORMAT.matcher(Objects.requireNonNull(encoded, "encoded"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid LexoRank: " + encoded);
        }
        return new LexoRank(Integer.parseInt(matcher.group(1)), new BigInteger(matcher.group(2), 36));
    }

    public static LexoRank middle() {
        return new LexoRank(0, LIMIT.divide(BigInteger.TWO));
    }

    public static LexoRank between(LexoRank lower, LexoRank upper) {
        if (lower == null && upper == null) {
            return middle();
        }
        final int bucket = lower != null ? lower.bucket : Objects.requireNonNull(upper).bucket;
        if (lower != null && upper != null && (lower.bucket != upper.bucket || lower.compareTo(upper) >= 0)) {
            throw new IllegalArgumentException("Ranks must be ordered in the same bucket");
        }
        final BigInteger low = lower == null ? BigInteger.ZERO : lower.value;
        final BigInteger high = upper == null ? LIMIT : upper.value;
        final BigInteger candidate = low.add(high).divide(BigInteger.TWO);
        if (candidate.equals(low) || candidate.equals(high)) {
            throw new ExhaustedException();
        }
        return new LexoRank(bucket, candidate);
    }

    public static List<LexoRank> rebalance(int size, int currentBucket) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        final int bucket = (currentBucket + 1) % 3;
        final BigInteger step = LIMIT.divide(BigInteger.valueOf((long) size + 1));
        final List<LexoRank> result = new ArrayList<>(size);
        for (int index = 1; index <= size; index++) {
            result.add(new LexoRank(bucket, step.multiply(BigInteger.valueOf(index))));
        }
        return List.copyOf(result);
    }

    public int bucket() {
        return bucket;
    }

    @Override
    public int compareTo(LexoRank other) {
        final int bucketOrder = Integer.compare(bucket, other.bucket);
        return bucketOrder != 0 ? bucketOrder : value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LexoRank rank && bucket == rank.bucket && value.equals(rank.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucket, value);
    }

    @Override
    public String toString() {
        final String digits = value.toString(36).toLowerCase(Locale.ROOT);
        return bucket + "|" + "0".repeat(WIDTH - digits.length()) + digits + ":";
    }

    public static final class ExhaustedException extends IllegalStateException {}
}
