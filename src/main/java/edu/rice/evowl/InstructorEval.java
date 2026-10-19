package edu.rice.evowl;

/**
 * The instructor evaluation object class
 */
public class InstructorEval {

    /**
     * The id of the group that was graded.
     */
    private String groupId;

    /**
     * The score that the group earned.
     */
    private double score;

    public InstructorEval(String groupId, double score) {
        this.groupId = groupId;
        this.score = score;
    }

    public String toString() {
        return "(groupId = " + groupId + ", score = " + score + ")";
    }

    /**
     * The id of the group that was graded.
     * @return the group id
     */
    public String getGroupId() {
        return this.groupId;
    }

    /**
     * The score that the group earned.
     * @return the score
     */
    public double getScore() {
        return this.score;
    }
}
