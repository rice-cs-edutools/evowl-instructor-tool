package edu.rice.evowl;

/**
 * Represents a single peer evaluation record: one student's pairwise comparison of two groups
 * on a single criterion.
 *
 * Scores use the 0–3 scale: 3 = group1 much better, 2 = group1 slightly better,
 * 1 = group2 slightly better, 0 = group2 much better.
 */
public class PeerEval {
    /** ID of the student who submitted this evaluation. */
    private String graderId;

    /** The group this student was evaluating (group 1 in the pair). */
    private String groupId;

    /** The group being compared against (group 2 in the pair). */
    private String otherGroupId;

    /** Pairwise score on the 0–3 scale. */
    private int score;

    /** Weight assigned to this evaluator based on their training performance. */
    private double weight;

    /**
     * Creates a new PeerEval.
     * @param graderId the ID of the student who submitted this evaluation
     * @param groupId the group this student was evaluating (group 1 in the pair)
     * @param otherGroupId the group being compared against (group 2 in the pair)
     * @param score the pairwise score on the 0–3 scale
     * @param weight the weight assigned to this evaluator based on their training performance
     */
    public PeerEval(String graderId, String groupId, String otherGroupId, int score, double weight) {
        this.graderId = graderId;
        this.groupId = groupId;
        this.otherGroupId = otherGroupId;
        this.score = score;
        this.weight = weight;
    }

    /**
     * Returns the ID of the student who submitted this evaluation.
     * @return the ID of the student who submitted this evaluation
     */
    public String getGraderId() {
        return graderId;
    }

    /**
     * Returns the group this student was evaluating (group 1 in the pair).
     * @return the group this student was evaluating (group 1 in the pair)
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Returns the group being compared against (group 2 in the pair).
     * @return the group being compared against (group 2 in the pair)
     */
    public String getOtherGroupId() { return otherGroupId; }

    /**
     * Returns the pairwise score on the 0–3 scale.
     * @return the pairwise score on the 0–3 scale
     */
    public int getScore() {
        return score;
    }

    /**
     * Returns the weight assigned to this evaluator based on their training performance.
     * @return the weight assigned to this evaluator based on their training performance
     */
    public double getWeight() {
        return weight;
    }
}
