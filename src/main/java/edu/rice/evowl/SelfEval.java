package edu.rice.evowl;

import java.util.*;

/**
 * Holds a student's self-evaluation data for one assignment: their before-eval responses
 * and (optionally) their after-eval responses.
 *
 * Both the before and after evals are keyed by criterion ID. When the same student submits
 * two rounds, the one with the lower uniqueID is treated as "before".
 */
public class SelfEval {

    /** The unique ID of the evaluation. */
    private int uniqueIdBefore;

    /** The before evaluation. */
    private Map<Integer, Integer> beforeEval;

    /** The after evaluation. */
    private Map<Integer, Integer> afterEval = new HashMap<>();

    /** The criteria IDs that were only evaluated after. */
    private List<Integer> afterOnly = new ArrayList<>();

    /**
     * Adds a self evaluation to the SelfEval object.
     * @param choiceStr the string of choices
     * @param criteriaIDs the criteria IDs
     * @param uniqueID the unique ID of the evaluation
     */
    public void addSelfEval(String choiceStr, List<Integer> criteriaIDs, Integer uniqueID) {
        Map<Integer, Integer> choices = new HashMap<Integer, Integer>();
        String[] choicesArray = choiceStr.substring(1, choiceStr.length() - 1).split(", ");

        for (int i = 0; i < criteriaIDs.size(); i++) {
            int crtieriaID = criteriaIDs.get(i);
            int choice = Integer.parseInt(choicesArray[i]);
            choices.put(crtieriaID, choice);
        }

        if (this.beforeEval == null) {
            this.beforeEval = choices;
            this.uniqueIdBefore = uniqueID;
        } else if (uniqueID.compareTo(this.uniqueIdBefore) < 0) {
            this.afterEval = this.beforeEval;
            this.beforeEval = choices;
            this.uniqueIdBefore = uniqueID;
        } else if (uniqueID.compareTo(this.uniqueIdBefore) > 0) {
            this.afterEval = choices;
        }

        if (choicesArray.length > criteriaIDs.size()) {
            for (int i = criteriaIDs.size(); i < choicesArray.length; i++) {
                this.afterOnly.add(Integer.parseInt(choicesArray[i]));
            }
        }
    }

    /**
     * Returns the before evaluation for a given criterion.
     * @param criteria the criterion ID
     * @return the before evaluation for the given criterion
     */
    public Integer getBeforeEval(Integer criteria) {
        return beforeEval.get(criteria);
    }
    
    /**
     * Returns the after evaluation for a given criterion.
     * @param criteria the criterion ID
     * @return the after evaluation for the given criterion
     */
    public Integer getAfterEval(Integer criteria) {
        return afterEval.get(criteria);
    }

    /**
     * Returns the criteria IDs in sorted order.
     * @return the criteria IDs in sorted order
     */
    public List<Integer> getCriteriaIDs() {
        List<Integer> sortedKeys = new ArrayList<>(beforeEval.keySet());
        Collections.sort(sortedKeys);
        return sortedKeys;
    }

    /**
     * Returns a string representation of the SelfEval object.
     * @return a string representation of the SelfEval object
     */
    public String toString() {
        // Place the before and after for each key back-to-back, leaving after blank if it wasn't completed
        StringBuilder sb = new StringBuilder();
        for (int key : this.getCriteriaIDs()) {
            int beforeVal = beforeEval.get(key);
            int afterVal = afterEval.getOrDefault(key, -1);
            String after = (afterVal == -1) ? "" : "" + afterVal;
            sb.append(beforeVal).append(",").append(after).append(",");
        }

        for (int elem : afterOnly) {
            sb.append(elem).append(",");
        }

        sb.deleteCharAt(sb.length() - 1);

        return sb.toString();
    }
}
