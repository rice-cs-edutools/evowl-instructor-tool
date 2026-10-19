package edu.rice.evowl;

/**
 * A simple typed pair of two values.
 */
public class Pair<A, B> {
    /** The first value in the pair. */
    private A first;

    /** The second value in the pair. */
    private B second;

    /**
     * Creates a new pair.
     * @param first the first value in the pair
     * @param second the second value in the pair
     */
    public Pair(A first, B second) {
        this.first = first;
        this.second = second;
    }

    /**
     * Returns the first value in the pair.
     * @return the first value in the pair
     */
    public A getFirst() {
        return this.first;
    }

    /**
     * Returns the second value in the pair.
     * @return the second value in the pair
     */
    public B getSecond() {
        return this.second;
    }
}