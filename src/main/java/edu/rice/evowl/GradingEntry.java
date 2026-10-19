package edu.rice.evowl;

/**
 * A grade entry where the score is the elo score of the group,
 * and whether the group was chosen for grading.
 */
public class GradingEntry {

    /** The score of the group. */
    private final double score;
    /** The name of the group. */
    private final String groupName;
    /** Whether the group was chosen for grading.*/
    private boolean isChosen;
    /** Whether the group was previously graded. */
    private boolean previouslyGraded;
    /** The grade of the group. */
    private Double grade;
    private boolean instructorGraded;

    /**
     * Creates a new GradingEntry.
     *
     * @param groupName the name of the group
     * @param score the elo score of the group
     * @param isChosen whether the group was chosen for grading
     */
    public GradingEntry(String groupName, double score, boolean isChosen, boolean previouslyGraded) {
        this.groupName = groupName;
        this.score = score;
        this.isChosen = isChosen;
        this.previouslyGraded = previouslyGraded;
        this.grade = null;
        this.instructorGraded = false;
    }

    /**
     * Returns the score of this group.
     */
    public double getScore() { return this.score; }

    /**
     * Returns the name of the group.
     * @return the name of this group
     */
    public String getGroupName() { return this.groupName; }

    /**
     * Returns whether this group was chosen for grading.
     * @return true if this group was chosen for grading, false otherwise
     */
    public boolean isChosen() { return this.isChosen; }

    /**
     * Returns whether this group was previously graded.
     * @return true if this group was previously graded, false otherwise
     */
    public boolean isPreviouslyGraded() {
        return this.previouslyGraded;
    }

    /**
     * Sets whether this group was previously graded.
     * @param previouslyGraded whether this group was previously graded
     */
    public void setPreviouslyGraded(boolean previouslyGraded) {
        this.previouslyGraded = previouslyGraded;
    }

    /**
     * Sets whether this group is chosen for grading.
     * @param isChosen whether this group is chosen for grading
     */
    public void setChosen(boolean isChosen) { this.isChosen = isChosen; }

    /**
     * Sets the grade of this group.
     * @param grade the grade of this group
     */
    public void setGrade(Double grade) {
        this.grade = grade;
    }

    /**
     * Returns the grade of this group.
     * @return the grade of this group
     */
    public Double getGrade() {
        return this.grade;
    }

    /**
     * Returns whether this group was graded by an instructor.
     * @return true if this group was graded by an instructor, false otherwise
     */
    public boolean isInstructorGraded() {
        return this.instructorGraded;
    }

    /**
     * Sets whether this group was graded by an instructor.
     * @param instructorGraded whether this group was graded by an instructor
     */
    public void setInstructorGraded(boolean instructorGraded) {
        this.instructorGraded = instructorGraded;
    }

    /**
     * Returns a string representation of this grading entry.
     */
    @Override
    public String toString() {
        return "(" + this.getGroupName() + ", " + this.getScore() + ", chosen=" +
                this.isChosen() + ", prevGraded=" + this.isPreviouslyGraded() + ")";
    }
}
