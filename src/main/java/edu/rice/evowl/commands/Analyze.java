package edu.rice.evowl.commands;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import edu.rice.evowl.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Implements the analyze command, which takes per-group ELO scores for each
 * grading criterion, divides groups into "tiers" based on score gaps, and selects one
 * representative group per tier to be graded by a TA.
 *
 * Output files are named analyze-<taId>.txt and record every group's ELO score,
 * whether it was previously graded, and whether it was chosen as the tier representative.
 * Those files feed the grade command.
 */
public class Analyze {

    /**
     * Fraction of the tier's score width used as a proximity tolerance when choosing a
     * representative that is near-but-not-exactly at the midpoint or endpoint.
     * Used by chooseMidpointWithTolerance and chooseEndpointsWithTolerance.
     */
    private static final double DEFAULT_TIER_TOLERANCE = 0.1;

    /**
     * Analyzes group ELO scores, divides them into tiers, selects grading targets, and writes
     * per-criterion output files to outputDir.
     *
     * @param numTiers             number of tiers to create; must not exceed the number of groups
     * @param location             strategy for picking a representative within each tier
     * @param groupElos            map of taId → (groupName → ELO score)
     * @param criteriaPeerToTaId   map of peer criterionId → taId
     * @param criterionInstructorEval map of taId → previously-recorded instructor evaluations
     * @param outputDir            directory where analyze-<taId>.txt files are written;
     *                             must not already exist (use --overwrite to replace)
     */
    public static void analyzeGroupElos(int numTiers, LocationToGrade location,
                                        Map<Integer, Map<String, Double>> groupElos,
                                        Map<Integer, Integer> criteriaPeerToTaId,
                                        Map<Integer, Set<InstructorEval>> criterionInstructorEval,
                                        String outputDir) {

        File outputDirFile = new File(outputDir);
        if (outputDirFile.exists()) {
            System.out.println("Output directory already exists. Please delete the directory and try again.");
            return;
        }

        // {taId: [0, group1-group2, group2-group3, group4-group5, ...]}
        Map<Integer, List<Double>> groupDeltas = new HashMap<>();
        // {taId: [ (group, score, prevGraded, chosenToGrade), ...]}
        Map<Integer, List<GradingEntry>> groupGradingEntries = new HashMap<>();
        // {taId: {score: [gradingEntry, ...], ...}}
        Map<Integer, Map<Double, List<GradingEntry>>> scoreToGroupGradingEntries = new HashMap<>();

        Map<Integer, SummaryStatistics> summaryStats = new HashMap<>();
        Set<String> groupsToGrade = new HashSet<>();

        loadData(groupElos, groupDeltas, groupGradingEntries, scoreToGroupGradingEntries,
                criteriaPeerToTaId, criterionInstructorEval,  summaryStats, groupsToGrade);


        // Check if numTiers is a valid value
        if (numTiers > groupsToGrade.size()) {
            System.out.println("ERROR: numTiers > number of groups to grade " +
                    "(i.e., " + numTiers + " > " + groupsToGrade.size() + ")");
            return;
        }

        // Create tiers
        Map<Integer, List<List<GradingEntry>>> criterionToTiers = createTiers(numTiers, DEFAULT_TIER_TOLERANCE,
                groupDeltas, groupGradingEntries, summaryStats);

        // Select groups to grade
        selectGroupsToGrade(location, criterionToTiers, scoreToGroupGradingEntries);

        // Write results to files
        writeResultsToFiles(criterionToTiers, outputDir);
    }

    /**
     * Take all the (group, score) pairs for a given criterion (which is converted to a taId) then creates
     * a GradingEntry object and sort them in descending order. Then, computes the delta between each
     * consecutive pair while also computing the summary statistics for each criterion (taId).
     *
     * @param groupElos a map where the key, taId, maps to a map of groups to their score
     * @param groupDeltas a map where the key, taId, maps to a list of deltas values between each consecutive group
     * @param groupGradingEntries a map where the key, taId, maps to a list of grading entries (group, score, prevGraded, chosenToGrade)
     * @param scoreToGroupGradingEntries a map where the key, taId, maps to a map of score to a list of grading entries with that score
     * @param criteriaPeerToTaId a map where the key, criterionId, maps to a taId
     * @param criterionInstructorEval a map where the key, criterionId, maps to a set of InstructorEval objects
     * @param summaryStats a map where the key, taId, maps to a SummaryStatistics object
     * @param groupsToGrade the set of groups to grade
     */
    private static void loadData(Map<Integer, Map<String, Double>> groupElos,
                                 Map<Integer, List<Double>> groupDeltas,
                                 Map<Integer, List<GradingEntry>> groupGradingEntries,
                                 Map<Integer, Map<Double, List<GradingEntry>>> scoreToGroupGradingEntries,
                                 Map<Integer, Integer> criteriaPeerToTaId,
                                 Map<Integer, Set<InstructorEval>> criterionInstructorEval,
                                 Map<Integer, SummaryStatistics> summaryStats, Set<String> groupsToGrade) {

        // Map.Entry<Integer, Map<String, Double>> is of the form {criterionId: {groupName: score}}
        for (Map.Entry<Integer, Map<String, Double>> criterion : groupElos.entrySet()) {
            int taId = criteriaPeerToTaId.get(criterion.getKey());

            // Converts the (groupName, score) pairs for into a list of GradingEntry objects
            List<GradingEntry> criterionGradingEntries = new ArrayList<>();
            for (Map.Entry<String, Double> entry : criterion.getValue().entrySet()) {
                String groupName = entry.getKey();
                double groupScore = entry.getValue();
                boolean previouslyGraded = false;
                double taScore = 0.0;

                // If a group has been graded, mark previously graded and record its score
                Set<InstructorEval> groupInstructorEval = criterionInstructorEval.get(taId);
                if (groupInstructorEval != null) {
                    for (InstructorEval instrEval : groupInstructorEval) {
                        if (instrEval.getGroupId().equals(groupName)) {
                            previouslyGraded = true;
                            taScore = instrEval.getScore();
                            break;
                        }
                    }
                }

                GradingEntry groupEntry = new GradingEntry(groupName, groupScore, false, previouslyGraded);
                if (previouslyGraded) {
                    groupEntry.setGrade(taScore);
                }
                criterionGradingEntries.add(groupEntry);
                groupsToGrade.add(groupName);

                // Creates the mapping {eloScore: [groups/submissionsWithThatScore]}
                scoreToGroupGradingEntries.computeIfAbsent(taId, k -> new HashMap<>());
                scoreToGroupGradingEntries.get(taId).computeIfAbsent(groupScore, k -> new ArrayList<>());
                scoreToGroupGradingEntries.get(taId).get(groupScore).add(groupEntry);
            }

            // Sorts and stores the (group, score) pairs (where it's a GradingEntry)
            // for this criterion in descending order
            criterionGradingEntries.sort(Comparator.comparingDouble(GradingEntry::getScore).reversed());
            groupGradingEntries.put(taId, criterionGradingEntries);

            // Holds the deltas between consecutive scores in this criterion
            List<Double> criterionDeltas = new ArrayList<>();
            Double previousScore = null;

            // Store a summary statistics for this criterion
            SummaryStatistics stats = new SummaryStatistics();
            summaryStats.put(taId, stats);

            // Calculate the deltas between consecutive scores in this criterion, while storing the summary statistics
            for (GradingEntry group : criterionGradingEntries) {
                if (previousScore != null) {
                    criterionDeltas.add(previousScore - group.getScore());
                } else {
                    criterionDeltas.add(0.0);
                }

                previousScore = group.getScore();
                stats.addValue(group.getScore());
            }

            // Store the deltas for this criterion
            groupDeltas.put(taId, criterionDeltas);
        }
    }

    /**
     * Lumps the pairs of (group, score) into "tiers", where the divisions between groups are the numTiers - 1
     * largest deltas. If numTiers = 0, then its calculated using the standard deviation * thresholdFactor.
     *
     * @param numTiers the number of tiers to create
     * @param thresholdFactor the threshold factor to use if numTiers = 0
     * @param groupDeltas the deltas between consecutive scores in each criterion (taId)
     * @param gradingEntries the grading entries for each criterion (taId)
     * @param summaryStats the summary statistics for each criterion (taId)
     * @return a map from taId to a list of tiers, where each tier is a list of GradingEntry objects in that tier
     */
    private static Map<Integer, List<List<GradingEntry>>> createTiers(int numTiers,
                                                                      double thresholdFactor,
                                                                      Map<Integer, List<Double>> groupDeltas,
                                                                      Map<Integer, List<GradingEntry>> gradingEntries,
                                                                      Map<Integer, SummaryStatistics> summaryStats) {

        Map<Integer, List<List<GradingEntry>>> taIdToGroupTiers = new HashMap<>();
        Map<Integer, List<Integer>> tierBoundaries = new HashMap<>();
        // Lump the (group, score) pairs (GradingEntries) into numTiers,
        // where the divisions between groups are based on the n-1 largest deltas.
        for (Map.Entry<Integer, List<Double>> entry : groupDeltas.entrySet()) {
            int taId = entry.getKey();
            List<Double> criterionDeltas = entry.getValue();

            // Create a list of indices sorted by delta values in descending order
            List<Integer> sortedIndices = IntStream.range(0, criterionDeltas.size())
                    .boxed()
                    .sorted((i, j) -> Double.compare(criterionDeltas.get(j), criterionDeltas.get(i)))
                    .collect(Collectors.toList());

            // Calculate the number of tiers based on the standard deviation of the deltas if numTiers = 0
            int newNumTiers = numTiers;
            if (newNumTiers == 0) {
                double threshold = thresholdFactor * (summaryStats.get(taId).getStandardDeviation());
                newNumTiers = (int) criterionDeltas.stream().filter(delta -> delta >= threshold).count();
            }

            // Select the indices of the n-1 largest deltas
            List<Integer> boundaries = sortedIndices.stream()
                    .filter(index -> index > 0)
                    .limit(newNumTiers - 1).sorted().collect(Collectors.toList());

            // Check for ties in delta values
            if (numTiers > 1 && !boundaries.isEmpty()) {
                double lastSelectedDelta = criterionDeltas.get(boundaries.get(boundaries.size() - 1));
                int lastBoundaryIndex = boundaries.get(boundaries.size() - 1);  // Get the index of the last split

                // Find all indices AFTER the last split with the same delta value as the last selected delta (ties)
                List<Integer> tiedIndices = sortedIndices.stream()
                        .filter(i -> i > lastBoundaryIndex && criterionDeltas.get(i) == lastSelectedDelta)
                        .collect(Collectors.toList());

                int tiesCount = tiedIndices.size();
                if (tiesCount > 1) {
                    // Adjust the number of tiers to include all ties
                    int adjustedNumTiers = numTiers + (tiesCount - 1);  // Add enough tiers to handle all tied deltas

                    System.out.println("Tie detected in delta values. For criterion (in taId form) " + taId +
                            " Try this numTiers to include all ties: " + adjustedNumTiers);
                }
            }

            // Adds the last index to include all elements
            boundaries.add(criterionDeltas.size());
            tierBoundaries.put(taId, boundaries);
        }

        /* Create a map from taId to a list of tiers, where each tier
         is a list of GradingEntry objects and the groups within each tier are
         sorted in descending order of their scores */
        for (Map.Entry<Integer, List<GradingEntry>> entry : gradingEntries.entrySet()) {
            int taId = entry.getKey();

            List<GradingEntry> criterionGradingEntries = entry.getValue();
            List<Integer> boundaries = tierBoundaries.get(taId);

            int startIndex = 0;
            List<List<GradingEntry>> tiers = new ArrayList<>();

            // Iterate through the boundaries and create tiers
            for (int endIndex : boundaries) {
                List<GradingEntry> tier = criterionGradingEntries.subList(startIndex, endIndex);
                tiers.add(tier);
                startIndex = endIndex;
            }
            taIdToGroupTiers.put(taId, tiers);
        }
        return taIdToGroupTiers;
    }

    /**
     * For each criterion's list of tiers, selects the group(s) within each tier that a TA
     * should grade, based on the given location strategy.
     *
     * @param location              grading-target selection strategy
     * @param criterionToTiers      map of taId → list-of-tiers (each tier is a sorted list of GradingEntry)
     * @param scoreToGradingEntries map of taId → (ELO score → GradingEntries with that score), used
     *                              to resolve ties by preferring previously-graded groups
     */
    private static void selectGroupsToGrade(LocationToGrade location,
                                            Map<Integer, List<List<GradingEntry>>> criterionToTiers,
                                            Map<Integer, Map<Double, List<GradingEntry>>> scoreToGradingEntries) {

        for (Map.Entry<Integer, List<List<GradingEntry>>> entry : criterionToTiers.entrySet()) {
            int taId = entry.getKey();
            List<List<GradingEntry>> tiers = entry.getValue();

            // Always mark the first and last groups for grading
            double firstGroupScore = tiers.get(0).get(0).getScore();
            double lastGroupScore = tiers.get(tiers.size() - 1).get(tiers.get(tiers.size() - 1).size() - 1).getScore();
            List<GradingEntry> tiedGroupsForFirst = scoreToGradingEntries.get(taId).get(firstGroupScore);
            List<GradingEntry> tiedGroupsForLast = scoreToGradingEntries.get(taId).get(lastGroupScore);

            // Optimize for minimal grading by prioritizing previously graded
            // entries in ties for the first and last groups
            GradingEntry chosenFirstEntry = tiedGroupsForFirst.size() == 1 ?
                    tiedGroupsForFirst.get(0) :
                    tiedGroupsForFirst.parallelStream()
                            .filter(gradingEntry -> gradingEntry.isPreviouslyGraded()
                                    && tiers.get(0).contains(gradingEntry)) // Only want same scores within the same tier
                            .filter(GradingEntry::isPreviouslyGraded)
                            .findFirst()
                            .orElse(tiers.get(0).get(0));

            chosenFirstEntry.setChosen(true);

            // If the chosen entry is not the first entry in the tier, swap it with the first entry
            if (tiers.get(0).indexOf(chosenFirstEntry) != 0) {
                Collections.swap(tiers.get(0), 0, tiers.get(0).indexOf(chosenFirstEntry));
            }

            GradingEntry chosenLastEntry = tiedGroupsForLast.size() == 1 ?
                    tiedGroupsForLast.get(0) :
                    tiedGroupsForLast.parallelStream()
                            .filter(gradingEntry -> gradingEntry.isPreviouslyGraded()
                                    && tiers.get(tiers.size() - 1).contains(gradingEntry)) // Only want same scores within the same tier
                            .findFirst()
                            .orElse(tiers.get(tiers.size() - 1).get(tiers.get(tiers.size() - 1).size() - 1));

            chosenLastEntry.setChosen(true);

            // If the chosen entry is not the last entry, swap it with the last entry
            int lastTierIndex = tiers.size() - 1;
            if(tiers.get(lastTierIndex).indexOf(chosenLastEntry) != tiers.get(lastTierIndex).size() - 1) {
                Collections.swap(tiers.get(lastTierIndex),
                        tiers.get(lastTierIndex).size() - 1,
                        tiers.get(lastTierIndex).indexOf(chosenLastEntry));
            }

            // For each tier, choose a group based on the location strategy
            for (List<GradingEntry> tier : tiers) {
                switch (location) {
                    case STRICT_MIDPOINT -> chooseStrictMidpoint(tiers, tier, scoreToGradingEntries, taId);
                    case STRICT_ENDPOINTS -> chooseStrictEndpoints(tier, scoreToGradingEntries, taId);
                    case MIDPOINT_WITH_TOLERANCE -> chooseMidpointWithTolerance(tier);
                    case ENDPOINTS_WITH_TOLERANCE -> chooseEndpointsWithTolerance(tier);
                    case ANY -> chooseAny(tiers, tier);
                    case ENDPOINTS_AND_MIDPOINT -> {
                        chooseStrictEndpoints(tier, scoreToGradingEntries, taId);
                        chooseStrictMidpoint(tiers, tier, scoreToGradingEntries, taId);
                    }
                }
            }
        }
    }

    /**
     * Chooses the group that is at the strict midpoint of the tier by position.
     *
     * @param tier the tier to choose a group from
     * @param scoreToGradingEntries the map of scores to grading entries
     * @param taId the id of the TA grading the tier (also consider as criterionId)
     */
    private static void chooseStrictMidpoint(List<List<GradingEntry>> tiers,
                                             List<GradingEntry> tier,
                                             Map<Integer, Map<Double, List<GradingEntry>>> scoreToGradingEntries,
                                             int taId) {
        int midPoint;
        if (tier.size() % 2 == 0) {
            midPoint = (tier.size() / 2) - 1;
        } else {
            midPoint = tier.size() / 2;
        }
        double midPointScore = tier.get(midPoint).getScore();

        // Optimize for minimal grading by prioritizing previously graded entries in ties
        List<GradingEntry> tiedGroupsForMidpoint = scoreToGradingEntries.get(taId).get(midPointScore);

        GradingEntry chosenEntry = tiedGroupsForMidpoint.size() == 1 ?
                tiedGroupsForMidpoint.get(0) :
                tiedGroupsForMidpoint.parallelStream()
                        .filter(gradingEntry -> gradingEntry.isPreviouslyGraded()
                                && tier.contains(gradingEntry)
                                && !gradingEntry.isChosen()) // Only want same scores within the same tier & not already chosen
                        .findFirst()
                        .orElse(tier.get(midPoint));

        chosenEntry.setChosen(true);

        // Swap chosen entry to midpoint if it's not already there
        if (tier.indexOf(chosenEntry) != midPoint) {
            Collections.swap(tier, tier.indexOf(chosenEntry), midPoint);
        }
    }


    /**
     * Chooses the group that is at the strict endpoints of the tier by position.
     * @param tier the tier to choose a group from
     * @param scoreToGradingEntries the map of scores to grading entries
     * @param taId the id of the TA grading the tier (also consider as criterionId)
     */
    private static void chooseStrictEndpoints(List<GradingEntry> tier,
                                              Map<Integer, Map<Double, List<GradingEntry>>> scoreToGradingEntries,
                                              int taId) {

        double firstScore = tier.get(0).getScore();
        double lastScore = tier.get(tier.size() - 1).getScore();
        List<GradingEntry> tiedGroupsForFirst = scoreToGradingEntries.get(taId).get(firstScore);
        List<GradingEntry> tiedGroupsForLast = scoreToGradingEntries.get(taId).get(lastScore);

        // Choose first entry, and prioritize previously graded entries in ties
        GradingEntry chosenFirstEntry = tiedGroupsForFirst.size() == 1 ?
                tiedGroupsForFirst.get(0) :
                tiedGroupsForFirst.parallelStream()
                        .filter(gradingEntry -> gradingEntry.isPreviouslyGraded()
                                && tier.contains(gradingEntry)
                                && !gradingEntry.isChosen()) // Only want same scores within the same tier & not already chosen
                        .findFirst()
                        .orElse(tier.get(0));

        chosenFirstEntry.setChosen(true);
        if (tier.indexOf(chosenFirstEntry) != 0) {
            Collections.swap(tier, tier.indexOf(chosenFirstEntry), 0);
        }

        // Choose last entry, and prioritize previously graded entries in ties
        GradingEntry chosenLastEntry = tiedGroupsForLast.size() == 1 ?
                tiedGroupsForLast.get(0) :
                tiedGroupsForLast.parallelStream()
                        .filter(gradingEntry -> gradingEntry.isPreviouslyGraded()
                                && tier.contains(gradingEntry)
                                && !gradingEntry.isChosen()) // Only want same scores within the same tier & not already chosen
                        .findFirst()
                        .orElse(tier.get(tier.size() - 1));

        // Handles when the last entry has already been chosen because this tier
        // is the last tier, and the last entry is always chosen
        if (tier.get(tier.size() - 1).isChosen()) {
            return;
        }
        chosenLastEntry.setChosen(true);

        if (tier.indexOf(chosenLastEntry) != tier.size() - 1) {
            Collections.swap(tier, tier.indexOf(chosenLastEntry), tier.size() - 1);
        }
    }

    /**
     * Chooses the group that is at the midpoint of the tier by position with 10% tolerance of the tier width.
     *
     * @param tier the tier to choose a group from
     */
    private static void chooseMidpointWithTolerance(List<GradingEntry> tier) {
        int midpoint;
        if (tier.size() % 2 == 0) {
            midpoint = (tier.size() / 2) - 1;
        } else {
            midpoint = tier.size() / 2;
        }
        double tierDeltaDiff = tier.get(0).getScore() - tier.get(tier.size() - 1).getScore();
        double tolerance = DEFAULT_TIER_TOLERANCE * tierDeltaDiff;

        /* If no previously-graded group falls within the tolerance window around the midpoint,
           choose the exact midpoint position. */
        if (tier.stream().noneMatch(gradingEntry -> Math.abs(gradingEntry.getScore()
                - tier.get(midpoint).getScore()) <= tolerance && gradingEntry.isPreviouslyGraded())) {
            tier.get(midpoint).setChosen(true);
        } else {
            // Choose the first entry that is within 10% of the midpoint
            // and has been previously graded then set that entry to be graded
            GradingEntry chosenEntry = tier.stream()
                    .filter(gradingEntry -> Math.abs(gradingEntry.getScore()
                            - tier.get(midpoint).getScore()) <= tolerance && gradingEntry.isPreviouslyGraded())
                    .findFirst()
                    .orElse(tier.get(midpoint));

            chosenEntry.setChosen(true);
        }
    }

    /**
     * Chooses the group that is at the endpoints of the tier by position with 10% tolerance of the tier width.
     *
     * @param tier the tier to choose a group from
     */
    private static void chooseEndpointsWithTolerance(List<GradingEntry> tier) {
        double tierDeltaDiff = tier.get(0).getScore() - tier.get(tier.size() - 1).getScore();
        double tolerance = DEFAULT_TIER_TOLERANCE * tierDeltaDiff;

        /* Assuming score tolerance of 10% of the width of the tier, if no score within 10% of the
           endpoints has been previously graded, choose the endpoints */
        double topScore = tier.get(0).getScore();
        double bottomScore = tier.get(tier.size() - 1).getScore();

        boolean topGraded = tier.stream().noneMatch(gradingEntry ->
                Math.abs(gradingEntry.getScore() - topScore) <= tolerance
                        && gradingEntry.isPreviouslyGraded());

        boolean bottomGraded = tier.stream().noneMatch(gradingEntry ->
                Math.abs(gradingEntry.getScore() - bottomScore) <= tolerance
                        && gradingEntry.isPreviouslyGraded());

        if (topGraded) {
            tier.get(0).setChosen(true);
        } else {
            // Choose the first entry that is within 10% of the top
            // and has been previously graded then set that entry to be graded
            GradingEntry chosenEntry = tier.stream()
                    .filter(gradingEntry -> Math.abs(gradingEntry.getScore()
                            - tier.get(0).getScore()) <= tolerance && gradingEntry.isPreviouslyGraded())
                    .findFirst()
                    .orElse(tier.get(0));

            chosenEntry.setChosen(true);
        }
        if (bottomGraded) {
            tier.get(tier.size() - 1).setChosen(true);
        } else {
            // Choose the first entry that is within 10% of the last
            // and has been previously graded then set that entry to be graded
            GradingEntry chosenEntry = tier.stream()
                    .filter(gradingEntry -> Math.abs(gradingEntry.getScore() - bottomScore)
                            <= tolerance && gradingEntry.isPreviouslyGraded())
                    .findFirst()
                    .orElse(tier.get(tier.size() - 1));

            chosenEntry.setChosen(true);
        }
    }

    /**
     * Chooses a random group from the tier to be graded.
     *
     * @param tiers the list of tiers
     * @param tier the tiers of groups to choose from
     */
    private static void chooseAny(List<List<GradingEntry>> tiers, List<GradingEntry> tier) {
        // If this is the first or last tier, don't do anything since we've chosen the endpoints
        if (tiers.indexOf(tier) == 0 || tiers.indexOf(tier) == tiers.size() - 1) {
            return;
        }

        // If none of the groups have been graded, choose the midpoint
        if (tier.stream().noneMatch(GradingEntry::isPreviouslyGraded)) {
            // Will never consider tolerance since none have been graded, so picks the strict midpoint
            chooseMidpointWithTolerance(tier);
        } else { // Otherwise, choose a random group
            Random random = new Random();
            int randomIndex = random.nextInt(tier.size());
            tier.get(randomIndex).setChosen(true);
        }
    }

    /**
     * Writes the results of the grading to the output directory.
     * Files are of the form "analyze-taid.txt" and contain lines of the form
     * groupName,groupScore,prevGraded/notGraded/chosenToGrade,isChosen
     *
     * @param criterionToTiers the map of criterion to tiers
     * @param outputDir the output directory
     */
    private static void writeResultsToFiles(Map<Integer, List<List<GradingEntry>>> criterionToTiers, String outputDir) {
        // Loops through each ta-id in criterionToTiers (i.e., integer keys)
        // and create a directory for each named by the ta-id
        for (Map.Entry<Integer, List<List<GradingEntry>>> entry : criterionToTiers.entrySet()) {
            int taId = entry.getKey();

            File outputDirFile = new File(outputDir);
            if (!outputDirFile.exists()) {
                if (!outputDirFile.mkdirs()) {
                    System.err.println("Failed to create output directory: " + outputDirFile.getAbsolutePath());
                    return;
                }
            }

            // Build a string for each List<GradingEntry> (tier) in the List<List<GradingEntry>> following the format:
            // groupName,groupScore,prevGraded/notGraded/chosenToGrade,isChosen
            StringBuilder tierString = new StringBuilder();
            for (List<GradingEntry> tier : entry.getValue()) {
                for (GradingEntry gradingEntry : tier) {
                    tierString.append(gradingEntry.getGroupName()).append(",")
                            .append(gradingEntry.getScore())
                            .append(",")
                            .append(gradingEntry.isPreviouslyGraded() && gradingEntry.isChosen() ? "1" :
                                    gradingEntry.isPreviouslyGraded() ? "1" : gradingEntry.isChosen() ? "2" : "0")
                            .append(",")
                            .append(gradingEntry.isChosen())
                            .append(",")
                            .append(gradingEntry.getGrade() != null ? gradingEntry.getGrade().toString() : "")
                            .append("\n");
                }
                tierString.append("------------------\n");
            }
            tierString.delete(tierString.length() - 19, tierString.length());

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