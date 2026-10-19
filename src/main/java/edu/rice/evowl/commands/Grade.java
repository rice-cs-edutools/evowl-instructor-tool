package edu.rice.evowl.commands;

import edu.rice.evowl.GradingEntry;
import edu.rice.evowl.InstructorEval;

import java.io.*;
import java.util.*;

/**
 * Implements the grade command, which takes the per-criterion tier files produced by
 * analyze and interpolates final numeric scores for every group using the TA-graded
 * anchor points as boundaries.
 *
 * Output files share the same naming scheme as the input: grade-<taId>.txt.
 */
public class Grade {

    /** Prefix used by both the analyze output files and this command's output files. */
    public static final String ANALYZE_FILE_PREFIX = "analyze-";

    /** Extension of the tier/grade files. */
    public static final String GRADE_FILE_EXTENSION = ".txt";
    
    /**
     * Determines the scores (grades) for the groups in the tiers.
     *
     * @param taIdToGroups            map of taId to the groups to grade.
     * @param criterionInstructorEval map of taId to the instructor eval for the criterion.
     * @param outputDir               the directory to write the results to.
     */
    public static void determineScores(Map<Integer, List<GradingEntry>> taIdToGroups,
                                       Map<Integer, Set<InstructorEval>> criterionInstructorEval,
                                       String outputDir) {

        System.out.println("Grading " + taIdToGroups.size() + " criterion/TA group(s)...");

        for (Map.Entry<Integer, List<GradingEntry>> entry : taIdToGroups.entrySet()) {
            int taId = entry.getKey();
            List<GradingEntry> tiers = entry.getValue();
            Set<InstructorEval> instructorEvals = criterionInstructorEval.get(taId);

            if (instructorEvals == null) {
                System.out.println("Skipping taId " + taId + ": no instructor evaluations found.");
                continue;
            }

            // Phase 1: match each grading entry to its instructor-graded score
            for (GradingEntry gradingEntry : tiers) {
                String groupName = gradingEntry.getGroupName();
                
                InstructorEval matchingEval = null;
                for (InstructorEval eval : instructorEvals) {
                    String evalGroupId = eval.getGroupId();
                    boolean matches = evalGroupId.equals(groupName);
                    if (matches) {
                        matchingEval = eval;
                        break;
                    }
                }
                
                if (matchingEval != null) {
                    //System.out.println("  MATCH FOUND: Group '" + groupName + 
                    //           "' matched instructor eval with score " + matchingEval.getScore());
                    gradingEntry.setGrade(matchingEval.getScore());
                    gradingEntry.setInstructorGraded(true);
                }
            }
            
            // Phase 2: interpolate grades for non-chosen entries using chosen+instructor-graded as anchors
            for (int currentIndex = 0; currentIndex < tiers.size(); currentIndex++) {
                GradingEntry gradingEntry = tiers.get(currentIndex);
                
                // Skip entries that are "chosen", these serve as interpolation boundaries
                if (gradingEntry.isChosen()) {
                    continue;
                }
                
                // Find aboveGradedGroup: closest chosen+instructor-graded entry above currentIndex
                GradingEntry aboveGradedGroup = null;
                int aboveMinDistance = Integer.MAX_VALUE;

                for (int i = 0; i < tiers.size(); i++) {
                    GradingEntry candidate = tiers.get(i);
                    if (candidate.isChosen() && candidate.isInstructorGraded() && i > currentIndex) {
                        int distance = Math.abs(i - currentIndex);
                        if (distance < aboveMinDistance) {
                            aboveMinDistance = distance;
                            aboveGradedGroup = candidate;
                        }
                    }
                }
                
                // Find belowGradedGroup: closest chosen+instructor-graded entry below currentIndex
                GradingEntry belowGradedGroup = null;
                int belowMaxIndex = -1;

                for (int i = 0; i < tiers.size(); i++) {
                    GradingEntry candidate = tiers.get(i);
                    if (candidate.isChosen() && candidate.isInstructorGraded() && i < currentIndex) {
                        if (i > belowMaxIndex) {
                            belowMaxIndex = i;
                            belowGradedGroup = candidate;
                        }
                    }
                }
                
                if (aboveGradedGroup != null && belowGradedGroup != null) {
                    gradingEntry.setGrade(linearInterpolationHelper(
                            aboveGradedGroup, belowGradedGroup,
                            gradingEntry, aboveGradedGroup.getGrade(), belowGradedGroup.getGrade()));
                    gradingEntry.setInstructorGraded(false);
                } else {
                    System.out.println("Cannot interpolate for group '" + gradingEntry.getGroupName()
                            + "' (taId=" + taId + "): missing "
                            + (aboveGradedGroup == null ? "upper" : "lower") + " anchor.");
                }
            }
        }
        Map<Integer, Set<String>> inversions = detectInversions(taIdToGroups);

        writeResultsToFiles(taIdToGroups, inversions, outputDir);
    }

    /**
     * Determines the scores of a group without TA's given an upper and lower bound
     *
     * @param taIdToGroups an array of GradingEntry objects for all groups
     * @param upperBound the upper bound/ceiling of the grades.
     * @param lowerBound the lower bound/floor of the grades.
     * @param outputDir the directory to write the results to.
     */
    public static void determineScoresWithoutTa(Map<Integer, List<GradingEntry>> taIdToGroups, int upperBound, int lowerBound, String outputDir){
        // Iterate through each criterion and interpolate to determine grade
        for(Integer criteria: taIdToGroups.keySet()) {
            List<GradingEntry> gradingEntries = taIdToGroups.get(criteria);

            // Interpolate the elo scores to determine their grades with the floor/ceiling
            for (GradingEntry gradingEntry : gradingEntries) {
                gradingEntry.setGrade(linearInterpolationHelper(gradingEntries.get(0),
                        gradingEntries.get(gradingEntries.size()-1), gradingEntry, upperBound, lowerBound));
            }
        }
        writeResultsToFiles(taIdToGroups, new HashMap<>(), outputDir);
    }

    /**
     * Helper function for calculating an interpolated grade
     * @param aboveGroup the group whose elo score is setting the upper elo score
     * @param belowGroup the group whose elo score is setting the lower elo score
     * @param targetEntry the target group
     * @param upperBound the upper bound for the grade
     * @param lowerBound the lower bound for the grade
     * @return the linear interpolated grade for the target group
     */
    private static double linearInterpolationHelper(GradingEntry aboveGroup, GradingEntry belowGroup,
                                                    GradingEntry targetEntry, double upperBound, double lowerBound){
        // Determine the upper and lower elo rankings
        double aboveElo = aboveGroup.getScore();
        double belowElo = belowGroup.getScore();

        // Interpolate the elo scores to determine their grades
        return lowerBound + (upperBound - lowerBound) * (targetEntry.getScore() - belowElo) / (aboveElo - belowElo);
    }

    //public static void gradeWith

    /**
     * Detects inversions in the grading entries.
     *
     * @param taIdToGroups Map of TA ID to list of grading entries
     * @return Map of TA ID to set of groups with inversions
     */
    public static Map<Integer, Set<String>> detectInversions(Map<Integer, List<GradingEntry>> taIdToGroups) {
        Map<Integer, Set<String>> inversions = new HashMap<>();

        for (Map.Entry<Integer, List<GradingEntry>> entry : taIdToGroups.entrySet()) {
            int taId = entry.getKey();
            List<GradingEntry> tiers = entry.getValue();
            Set<String> groupsWithInversions = new HashSet<>();

            for (int i = 0; i < tiers.size() - 1; i++) {
                GradingEntry currentEntry = tiers.get(i);
                GradingEntry nextEntry = tiers.get(i + 1);

                // Check if Elo is ordered correctly but grades are not
                if (currentEntry.getScore() > nextEntry.getScore() && currentEntry.getGrade() < nextEntry.getGrade()) {
                    groupsWithInversions.add(currentEntry.getGroupName());
                    groupsWithInversions.add(nextEntry.getGroupName());
                }
            }

            inversions.put(taId, groupsWithInversions);
        }

        if (!inversions.isEmpty()) {
            System.out.println("At least one inversion was detected.");
        }
        return inversions;
    }

    /**
     * Reads the grading entries from the given file.
     * @param file the file to read from
     * @return a list of grading entries
     */
    public static List<GradingEntry> fileToGradingEntry(File file) {
        List<GradingEntry> gradingEntries = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;

            while ((line = br.readLine()) != null) {
                if (line.startsWith("------------------")) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length < 4) {
                    continue; // Skip malformed lines
                }

                String groupName = parts[0];
                double elo = Double.parseDouble(parts[1]);
                int previouslyGraded = Integer.parseInt(parts[2]);
                boolean wasChoosen = Boolean.parseBoolean(parts[3]);

                GradingEntry gradingEntry = new GradingEntry(
                        groupName,
                        elo,
                        wasChoosen,
                        previouslyGraded != 0
                );
                gradingEntries.add(gradingEntry);
            }
        } catch (IOException e) {
            System.err.println("Error reading grading file " + file + ": " + e.getMessage());
        }
        return gradingEntries;
    }

    /**
     * Writes the results of the grading to the output directory.
     *
     * @param taIdToGroups the map of criterion (ta id) to groups
     * @param inversions   the map of criterion (ta id) to groups with inversions
     * @param outputDir    the output directory
     */
    private static void writeResultsToFiles(Map<Integer, List<GradingEntry>> taIdToGroups,
                                            Map<Integer, Set<String>> inversions,
                                            String outputDir) {

        if (!inversions.isEmpty()) System.out.println("Inversions detected: " + inversions);
        for (Map.Entry<Integer, List<GradingEntry>> entry : taIdToGroups.entrySet()) {
            int taId = entry.getKey();
            List<GradingEntry> tiers = entry.getValue();

            File outputDirFile = new File(outputDir);
            if (!outputDirFile.exists()) {
                if (!outputDirFile.mkdirs()) {
                    System.err.println("Failed to create output directory: " + outputDirFile.getAbsolutePath());
                    return;
                }
            }

            StringBuilder tierString = new StringBuilder();

            for (GradingEntry gradingEntry : tiers) {
                tierString.append(gradingEntry.getGroupName()).append(",")
                        .append(gradingEntry.getScore())
                        .append(",")
                        .append(gradingEntry.getGrade())
                        .append(inversions.get(taId).contains(gradingEntry.getGroupName()) ?
                                ", *\n" : "\n");
            }

            // write the string to a file in the taDir directory
            File tierFile = new File(outputDirFile, "analyze-" + taId + ".txt");
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(tierFile))) {
                bw.write(tierString.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
