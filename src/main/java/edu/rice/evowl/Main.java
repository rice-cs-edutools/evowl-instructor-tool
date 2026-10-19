package edu.rice.evowl;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import edu.rice.evowl.commands.*;
import edu.rice.evowl.coverage.CoverageParser;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks the first argument (subcommand) and delegates to the
 * appropriate command class or helper method.
 *
 * Usage overview: run with no arguments (or --help) to see the full subcommand list.
 */
public class Main {

    /**
     * Minimum score value at which a peer evaluation is considered to favour group 1.
     * Scores >= this threshold mean group 1 is at least slightly better.
     */
    private static final int PEER_SCORE_G1_THRESHOLD = 2;

    static DataBaseCrud databaseConn;
    static DataBaseCrud instrDatabaseConn;

    /**
     * Loads DB connection properties from the first application.properties found:
     *   1. Path in EVOWL_CONFIG system property or environment variable.
     *   2. application.properties in the current working directory.
     *   3. application.properties in the same directory as the running jar.
     * 
     * Throws IllegalArgumentException with setup instructions if nothing is found or
     * a required key is missing.
     */
    private static java.util.Properties loadDbConfig() throws IOException {
        List<String> candidates = new ArrayList<>();
        String explicit = System.getProperty("EVOWL_CONFIG");
        if (explicit == null) explicit = System.getenv("EVOWL_CONFIG");
        if (explicit != null) candidates.add(explicit);
        candidates.add("application.properties");
        try {
            String jarDir = new File(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath()).getParent();
            if (jarDir != null) candidates.add(jarDir + File.separator + "application.properties");
        } catch (Exception ignored) {}

        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                java.util.Properties props = new java.util.Properties();
                try (FileInputStream fis = new FileInputStream(f)) {
                    props.load(fis);
                }
                for (String key : new String[]{"evowl.db.url.template", "evowl.db.username", "evowl.db.password"}) {
                    String val = props.getProperty(key);
                    if (val == null || val.isBlank()) {
                        throw new IllegalArgumentException(
                            "Missing required key '" + key + "' in " + f.getAbsolutePath() + ". " +
                            "Please refer to the README.md for instructions.");
                    }
                }
                return props;
            }
        }
        throw new IllegalArgumentException("No application.properties found. Please refer to the README.md for instructions.");
    }

    public static void main(String[] args) throws SQLException, IOException, ParseException {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws SQLException, IOException, ParseException {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            Cli.printHelp();
            return;
        }

        String command = args[0];
        // Parse flags once; used both for DB setup and for the command dispatch below
        Cli cli = Cli.parse(args, 1);

        if (cli.isHelp()) {
            Cli.printHelp(command);
            return;
        }

        boolean connectToDb = !command.equals("parse") && !command.equals("metrics") && !command.equals("default-config");
        if (connectToDb) {
            java.util.Properties dbConfig = loadDbConfig();
            String jdbcUrlTemplate = dbConfig.getProperty("evowl.db.url.template");
            String dbUser = dbConfig.getProperty("evowl.db.username");
            String dbPassword = dbConfig.getProperty("evowl.db.password");
            String dbName = cli.require("db");

            // Connect without DB selected first so createAllTables can create it
            databaseConn = new DataBaseCrud(dbName);
            databaseConn.connect(String.format(jdbcUrlTemplate, ""), dbUser, dbPassword);
            databaseConn.createAllTables();
            databaseConn.connect(String.format(jdbcUrlTemplate, dbName), dbUser, dbPassword);

            // Commands that also need a connection to an instructor DB
            boolean needsInstrDb = command.equals("grade-instr");
            if (needsInstrDb) {
                String instrDbName = cli.optional("instr-db", null);
                if (instrDbName != null) {
                    instrDatabaseConn = new DataBaseCrud(instrDbName);
                    instrDatabaseConn.connect(String.format(jdbcUrlTemplate, ""), dbUser, dbPassword);
                    instrDatabaseConn.createAllTables();
                    instrDatabaseConn.connect(String.format(jdbcUrlTemplate, instrDbName), dbUser, dbPassword);
                }
            }
        }

        switch (command) {
            case "roster" -> {
                String pathToRosterFile = cli.require("file");
                loadRoster(pathToRosterFile);
            }
            case "groups" -> {
                String pathToGroupsFile = cli.require("file");
                loadGroups(pathToGroupsFile, cli.isOverwrite());
            }
            case "list-assignments" -> {
                java.util.List<String[]> assignments = databaseConn.listAssignments();
                if (assignments.isEmpty()) {
                    System.out.println("No assignments found in database.");
                } else {
                    System.out.printf("%-12s %-30s %s%n", "ID", "Name", "Deadline");
                    System.out.println("-".repeat(70));
                    for (String[] row : assignments) {
                        System.out.printf("%-12s %-30s %s%n", row[0], row[1], row[2]);
                    }
                }
            }
            case "assignment" -> {
                String name = cli.require("name");
                String studentSubmissionDdl = cli.require("deadline");
                String pathToCriteriaFile = cli.require("criteria");
                String pathToSubmissionsDirectory = cli.require("submissions");
                if (databaseConn.assignmentExistsByName(name)) {
                    if (!cli.isOverwrite()) {
                        System.out.println("Assignment \"" + name + "\" already exists in this database.");
                        System.out.println("Re-run with --overwrite to replace it, or use a different --name.");
                        return;
                    }
                    int oldId;
                    while ((oldId = databaseConn.getAssignmentIdByName(name)) != -1) {
                        System.out.println("Deleting existing assignment \"" + name + "\" (id=" + oldId + ")...");
                        databaseConn.deleteAssignmentCascade(oldId);
                    }
                }
                createAssignment(name, studentSubmissionDdl, pathToCriteriaFile, pathToSubmissionsDirectory);
            }
            case "default-config" -> {
                Language lang = cli.requireLanguage();
                String resourcePath = "instructor-files/default_config_" + lang.name().toLowerCase() + ".json";
                try (InputStream is = Main.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new IllegalArgumentException("No bundled default config for language: " + lang.name().toLowerCase());
                    }
                    System.out.print(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            case "grades" -> {
                int assignId = cli.requireInt("assign");
                String kind = cli.require("kind");
                String out = cli.require("out");
                switch (kind) {
                    case "mc"   -> loadPeerMC(assignId, out, new HashMap<String, Double>());
                    case "fr"   -> loadPeerFR(assignId, out);
                    case "pe"   -> loadPeerEvalCompletion(assignId, out);
                    case "semc" -> loadSelfMC(assignId, out);
                    case "sefr" -> loadSelfFR(assignId, out);
                    case "inmc" -> loadInstructorMC(assignId, out);
                    default -> System.out.println("Unknown grade kind \"" + kind + "\". Valid: mc, fr, pe, semc, sefr, inmc");
                }
            }
            case "parse" -> {
                Cli parseCli = Cli.parse(args, 1);
                Language parseLang = parseCli.requireLanguage();
                String pathToSubmissionsDirectory = parseCli.require("submissions");

                DirectoryStream<Path> directoryStream =
                        Files.newDirectoryStream(Paths.get(pathToSubmissionsDirectory));
                for (Path path : directoryStream) {
                    if (Files.isDirectory(path)) {
                        String subPath = path.toString();
                        Parse.parseFiles(parseLang, subPath, Parse.COVERAGE_AFTER_DIR);
                        System.out.println("Parsed: " + subPath + File.separator + Parse.COVERAGE_AFTER_DIR);
                    }
                }
                return;
            }
            case "metrics" -> {
                Cli metricsCli = Cli.parse(args, 1);
                Language metricsLang = metricsCli.requireLanguage();
                String metricsDir = metricsCli.require("submissions");
                Metrics.computeMetrics(metricsLang, metricsDir, Parse.COVERAGE_AFTER_DIR);
                return;
            }
            case "extension" -> {
                // assignId is accepted but currently ignored (extensions apply across all future assignments)
                int assignId = cli.requireInt("assign");
                String userId = cli.require("userId");
                String extendedDeadline = cli.require("deadline");
                databaseConn.addExtension(userId, extendedDeadline);
            }
            case "export" -> {
                int assignId = cli.requireInt("assign");
                String type = cli.require("kind");
                if (type.equals("peer")) {
                    Map<Integer, List<PeerEval>> data = databaseConn.dataLoadPeerMC(assignId);
                    StringBuilder sb = new StringBuilder();

                    // Header row
                    sb.append("criteria,userId,group1,group2,score,weight\n");

                    // Body
                    for (Map.Entry<Integer, List<PeerEval>> entry : data.entrySet()) {
                        Integer key = entry.getKey();
                        List<PeerEval> evals = entry.getValue();

                        for (PeerEval eval : evals) {
                            sb.append(key).append(",");
                            sb.append(eval.getGraderId()).append(",");
                            sb.append(eval.getGroupId()).append(",");
                            sb.append(eval.getOtherGroupId()).append(",");
                            sb.append(eval.getScore()).append(",");
                            sb.append(eval.getWeight()).append("\n");
                        }
                    }

                    System.out.println(sb.toString());

                } else if (type.equals("self")) {
                    Map<String, SelfEval> data = databaseConn.dataLoadSelfMC(assignId);
                    StringBuilder allSb = new StringBuilder();

                    // Header row
                    allSb.append("student,beforeAnswers,afterAnswers\n");

                    // Body
                    student: for (Map.Entry<String, SelfEval> entry : data.entrySet()) {
                        String userId = entry.getKey();
                        SelfEval selfEval = entry.getValue();

                        StringBuilder sb = new StringBuilder();

                        sb.append(userId).append(",");

                        // Add the before eval
                        for (Integer criteriaId : selfEval.getCriteriaIDs()) {
                            Integer value = selfEval.getBeforeEval(criteriaId);
                            if (value == null) {
                                continue student;
                            }
                            sb.append(value).append(" ");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                        sb.append(",");

                        // Add the after eval
                        for (Integer criteriaId : selfEval.getCriteriaIDs()) {
                            Integer value = selfEval.getAfterEval(criteriaId);
                            if (value == null) {
                                continue student;
                            }
                            sb.append(value).append(" ");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                        allSb.append(sb).append("\n");
                    }

                    System.out.println(allSb.toString());
                } else if (type.equals("training")) {

                    Map<String, Map<Integer, List<Integer>>> data = databaseConn.dataLoadAllTrainingMC(assignId);
                    StringBuilder sb = new StringBuilder();

                    // Header row
                    sb.append("userId,trainingIdx,answers\n");

                    // Answers
                    List<List<Integer>> answers = databaseConn.getAnswers(assignId);
                    for (int trainingIdx = 0; trainingIdx < answers.size(); trainingIdx++) {
                        sb.append("correct").append(",");
                        sb.append(trainingIdx).append(",");

                        // Add answers
                        for (Integer answer : answers.get(trainingIdx)) {
                            sb.append(answer).append(" ");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                        sb.append("\n");
                    }


                    // Body
                    for (Map.Entry<String, Map<Integer, List<Integer>>> entry : data.entrySet()) {
                        String userId = entry.getKey();
                        Map<Integer, List<Integer>> trainings = entry.getValue();

                        for (Map.Entry<Integer, List<Integer>> trainingEntry : trainings.entrySet()) {
                            sb.append(userId).append(",");
                            sb.append(trainingEntry.getKey()).append(",");

                            // Add answers
                            for (Integer answer : trainingEntry.getValue()) {
                                sb.append(answer).append(" ");
                            }
                            sb.deleteCharAt(sb.length() - 1);
                            sb.append("\n");
                        }
                    }

                    System.out.println(sb.toString());

                } else if (type.equals("instructor")) {
                    List<Integer> instructorCriteriaNums = databaseConn.getInstructorEvalMCCriteriaIDs(assignId);
                    Map<Integer, Set<InstructorEval>> instructorEvals = databaseConn.dataLoadInstructorMC(assignId);

                    StringBuilder sb = new StringBuilder();
                    sb.append("criteria,group,grade\n");

                    System.out.println("Instructor Criteria Ids: " + instructorCriteriaNums);
                    System.out.println("Instructor Evals: " + instructorEvals);

                    for (int criteriaIdx = 0; criteriaIdx < 5; criteriaIdx++) {
                        Integer instructorCriteria = instructorCriteriaNums.get(criteriaIdx);
                        for (InstructorEval eval : instructorEvals.get(instructorCriteria)) {
                            sb.append(criteriaIdx + 1).append(",");
                            sb.append(eval.getGroupId()).append(",");
                            sb.append(eval.getScore());
                            sb.append("\n");
                        }
                    }

                    System.out.println(sb.toString());

                } else {
                    System.out.println("Invalid export type");
                }
            }
            case "analyze" -> {
                int assignId = cli.requireInt("assign");
                int numTiers = cli.requireInt("tiers");
                LocationToGrade location = cli.requireLocation();
                String outputDir = cli.require("out");

                File analyzeOutputDir = new File(outputDir);
                if (analyzeOutputDir.exists() && !cli.isOverwrite()) {
                    System.out.println("Output directory already exists: " + outputDir);
                    System.out.println("Re-run with --overwrite to replace it, or choose a different --out path.");
                    return;
                }
                if (analyzeOutputDir.exists() && cli.isOverwrite()) {
                    FileUtils.deleteDirectory(analyzeOutputDir);
                }


                Map<Integer, List<PeerEval>> questionScores = databaseConn.dataLoadPeerMC(assignId);
                List<String> groupIDs = databaseConn.getGroupIdsForAssignment(assignId);

                Map<String, Double> sIdToWeight = new HashMap<>();
                List<String> sIds = databaseConn.getStudentUserIds();
                for(String sId: sIds){
                    sIdToWeight.put(sId, 1.0);
                }
                Map<Integer, Map<String, Double>> groupElos = computeEloRatings(questionScores, groupIDs, sIdToWeight);

                Map<Integer, Integer> criteriaPeerToTaId = new HashMap<>();
                for (Integer criterionId : groupElos.keySet()) {
                    Integer taId = databaseConn.criteriaPeerToTaId(criterionId);
                    if (taId == null) {
                        System.err.println("ERROR: No criteria_correspondence entry for peer criterion " + criterionId
                                + " in assignment " + assignId + ".");
                        System.err.println("       Run 'evowl list-assignments --db=<db>' to verify the assignment ID, "
                                + "or re-run 'evowl assignment' to recreate it.");
                        return;
                    }
                    criteriaPeerToTaId.put(criterionId, taId);
                }

                Map<Integer, Set<InstructorEval>> criterionInstructorEval = databaseConn.dataLoadInstructorMC(assignId);

                Analyze.analyzeGroupElos(numTiers, location, groupElos, criteriaPeerToTaId,
                        criterionInstructorEval, outputDir);
            }
            case "grade" -> {
                int assignId = cli.requireInt("assign");
                String inputDir = cli.require("in");
                String outputDir = cli.require("out");

                File gradeOutputDir = new File(outputDir);
                if (gradeOutputDir.exists() && !cli.isOverwrite()) {
                    System.out.println("Output directory already exists: " + outputDir);
                    System.out.println("Re-run with --overwrite to replace it, or choose a different --out path.");
                    return;
                }
                if (gradeOutputDir.exists()) FileUtils.deleteDirectory(gradeOutputDir);

                Map<Integer, List<GradingEntry>> taIdToGroups = new HashMap<>();
                for (String file : FileUtils.getFilenames(inputDir, "txt")) {
                    int taId = Integer.parseInt(file.substring(Grade.ANALYZE_FILE_PREFIX.length(),
                            file.length() - Grade.GRADE_FILE_EXTENSION.length()));
                    taIdToGroups.put(taId, Grade.fileToGradingEntry(new File(inputDir + File.separator + file)));
                }

                Map<Integer, Set<InstructorEval>> criterionInstructorEval = databaseConn.dataLoadInstructorMC(assignId);
                Grade.determineScores(taIdToGroups, criterionInstructorEval, outputDir);
            }
            case "grade-instr" -> {
                // Uses instructor grades from --instr-db as interpolation anchors instead of TA grades
                int assignId = cli.requireInt("assign");
                String inputDir = cli.require("in");
                String outputDir = cli.require("out");
                int instrAssignId = cli.requireInt("instr-assign");

                File gradeInstrOutputDir = new File(outputDir);
                if (gradeInstrOutputDir.exists() && !cli.isOverwrite()) {
                    System.out.println("Output directory already exists: " + outputDir);
                    System.out.println("Re-run with --overwrite to replace it, or choose a different --out path.");
                    return;
                }
                if (gradeInstrOutputDir.exists()) FileUtils.deleteDirectory(gradeInstrOutputDir);

                System.out.println("Using instructor DB grades as interpolation anchors");

                // load grading entries from analyze files (determines where anchors are via isChosen)
                Map<Integer, List<GradingEntry>> taIdToGroups = new HashMap<>();
                for (String file : FileUtils.getFilenames(inputDir, "txt")) {
                    int taId = Integer.parseInt(file.substring(Grade.ANALYZE_FILE_PREFIX.length(),
                            file.length() - Grade.GRADE_FILE_EXTENSION.length()));
                    taIdToGroups.put(taId, Grade.fileToGradingEntry(new File(inputDir + File.separator + file)));
                }

                // load instructor grades from the instructor DB (determines anchor grades)
                Map<Integer, Set<InstructorEval>> instrEvals;
                if (instrDatabaseConn != null) {
                    instrEvals = instrDatabaseConn.dataLoadInstructorMC(instrAssignId);
                    System.out.println("Loaded instructor evals for " + instrEvals.size() + " criteria");
                } else {
                    System.out.println("Instructor DB connection failed");
                    return;
                }

                // criteria with no instructor grades
                List<Integer> oldTaIdKeys = new ArrayList<>(taIdToGroups.keySet());
                for (Integer taId : oldTaIdKeys) {
                    Set<InstructorEval> evals = instrEvals.get(taId);
                    
                    if (evals == null || evals.isEmpty()) {
                        System.out.println("No instructor grades for criterion " + taId + ", skipping...");
                        taIdToGroups.remove(taId);
                    }
                }
                
                // Run grading with instructor grades as anchors
                Grade.determineScores(taIdToGroups, instrEvals, outputDir);

                // mse, compare interpolated grades to ground truth instructor grades
                System.out.println("\n=== MSE COMPUTATION ===");
                double totalSquaredError = 0.0;
                int totalComparisons = 0;
                
                for (Map.Entry<Integer, List<GradingEntry>> entry : taIdToGroups.entrySet()) {
                    int taId = entry.getKey();
                    List<GradingEntry> entries = entry.getValue();
                    Set<InstructorEval> truthSet = instrEvals.get(taId);
                    
                    if (truthSet == null) {
                        System.out.println("No ground truth for taId " + taId);
                        continue;
                    }
                    
                    // Building lookup map for ground truth
                    Map<String, Double> groundTruth = new HashMap<>();
                    for (InstructorEval ie : truthSet) {
                        groundTruth.put(ie.getGroupId(), ie.getScore());
                    }
                    
                    double criterionSquaredError = 0.0;
                    int criterionCount = 0;
                    
                    for (GradingEntry ge : entries) {
                        Double truth = groundTruth.get(ge.getGroupName());
                        Double interpolated = ge.getGrade();
                        
                        if (truth != null && interpolated != null) {
                            double error = interpolated - truth;
                            criterionSquaredError += error * error;
                            criterionCount++;
                        }
                    }
                    
                    if (criterionCount > 0) {
                        double mse = criterionSquaredError / criterionCount;
                        System.out.println("taId=" + taId + " MSE=" + mse + " (n=" + criterionCount + ")");
                        totalSquaredError += criterionSquaredError;
                        totalComparisons += criterionCount;
                    }
                }
                
                if (totalComparisons > 0) {
                    double overallMse = totalSquaredError / totalComparisons;
                    System.out.println("OVERALL MSE=" + overallMse + " (n=" + totalComparisons + ")");
                }
                System.out.println("=== END MSE ===");
            }
            case "grade-wo-tas" -> {
                int lowerBound = cli.requireInt("lower");
                int upperBound = cli.requireInt("upper");
                String inputDir = cli.require("in");
                String outputDir = cli.require("out");

                File gradeWoTasOutputDir = new File(outputDir);
                if (gradeWoTasOutputDir.exists() && !cli.isOverwrite()) {
                    System.out.println("Output directory already exists: " + outputDir);
                    System.out.println("Re-run with --overwrite to replace it, or choose a different --out path.");
                    return;
                }
                if (gradeWoTasOutputDir.exists()) FileUtils.deleteDirectory(gradeWoTasOutputDir);

                Map<Integer, List<GradingEntry>> taIdToGroups = new HashMap<>();
                for (String file : FileUtils.getFilenames(inputDir, "txt")) {
                    int taId = Integer.parseInt(file.substring(Grade.ANALYZE_FILE_PREFIX.length(),
                            file.length() - Grade.GRADE_FILE_EXTENSION.length()));
                    taIdToGroups.put(taId, Grade.fileToGradingEntry(new File(inputDir + File.separator + file)));
                }
                Grade.determineScoresWithoutTa(taIdToGroups, upperBound, lowerBound, outputDir);
            }
            case "assign-tas" -> {
                int assignId = cli.requireInt("assign");
                String dirpath = cli.require("in");
                assignRound2Grading(assignId, dirpath);
            }
            default -> System.out.println("Command not found: \"" + command + "\"");
        }

        if (connectToDb) {
            databaseConn.closeConnection();
            if (instrDatabaseConn != null) {
                instrDatabaseConn.closeConnection();
            }
        }
    }

    /**
     * assign-tas command: takes a path to a directory containing one file per each criterion in the csv
     * format output by the analyze command and creates the new instructor eval assignments that are needed
     * in order to fill in any gaps in the original grading distribution. Assumes that the files are
     * named after the corresponding ta_ids from the criteria_correspondence table, e.g. analyze15.csv,
     * analyze16.csv, etc.
     *
     * @param assignId the assignment to be graded
     * @param dirpath a file of the format output by the analyze command
     */
    private static void assignRound2Grading(int assignId, String dirpath) throws IOException {
        System.out.println("Creating round 2 grading assignments...");
        Set<String> filenames = FileUtils.getFilenames(dirpath, "txt");

        // Determine which assignments still need to be graded for each criteria
        Map<Integer, Set<String>> toBeGraded = new HashMap<>();
        for (String filename : filenames) {
            int taCriteriaId = Integer.parseInt(filename.substring(8, filename.length() - 4));
            toBeGraded.put(taCriteriaId, new HashSet<>());
            String contents = Files.readString(Paths.get(dirpath + File.separator + filename));
            String[] lines = contents.split("\n");
            for (String line : lines) {
                if (line.startsWith("-")) {
                    continue;
                }
                String[] elems = line.split(",");
                String groupName = elems[0];
                //double eloScore = Double.parseDouble(elems[1]);
                int status = Integer.parseInt(elems[2]);

                // status = 0: does not need to be graded
                // status = 1: was graded during round 1
                // status = 2: needs to be graded during round 2

                if (status == 2) {
                    // Mark that this needs to be graded
                    toBeGraded.get(taCriteriaId).add(groupName);
                }
            }
        }
        System.out.println("to be graded: " + toBeGraded);

        // Look up which TA(s) were originally supposed to grade which criteria
        Map<Integer, Set<String>> criteriaToTAs = databaseConn.getInverseInstructorCriteriaAssignment(assignId);
        System.out.println("criteria to TAs: " + criteriaToTAs);
        Map<String, Integer> taToNextIdx = new HashMap<>();
        Map<String, Integer> taToOffset = new HashMap<>();

        // Create new assignments
        for (Map.Entry<Integer, Set<String>> pendingGrading : toBeGraded.entrySet()) {
            int criteriaId = pendingGrading.getKey();
            List<String> groups = new ArrayList<>(pendingGrading.getValue());
            List<String> tas = new ArrayList<>(criteriaToTAs.get(criteriaId));
            int numTas = tas.size();
            int numGroups = groups.size();
            int minChunkSize = numGroups / numTas;
            int maxChunkSize = minChunkSize + 1;
            int numChunksOfMaxSize = Math.round((((float)numGroups / numTas) - minChunkSize) * numTas);

            for (int taIdx = 0; taIdx < numTas; taIdx++) {
                String taId = tas.get(taIdx);
                int nextIdx = 0;
                if (!taToNextIdx.containsKey(taId)) {
                    nextIdx = databaseConn.getNextIndexGradingAssignment(assignId, taId);
                    taToOffset.put(taId, databaseConn.getOffsetRound2GradingAssignment(assignId, taId));
                    taToNextIdx.put(taId, nextIdx);
                } else {
                    nextIdx = taToNextIdx.get(taId);
                }
                int chunkSize;
                int chunkStart;
                if (taIdx >= numChunksOfMaxSize) {
                    chunkSize = minChunkSize;
                    chunkStart = Math.round((maxChunkSize * numChunksOfMaxSize) + (minChunkSize * (taIdx - numChunksOfMaxSize)));
                } else { // taIdx < numChunksOfMaxSize
                    chunkSize = maxChunkSize;
                    chunkStart = Math.round(maxChunkSize * taIdx);
                }
                int chunkEnd = chunkStart + chunkSize;
                List<String> chunk = groups.subList(chunkStart, chunkEnd);
                for (String group : chunk) {
                    // offset is used to compute the index *for round 2*
                    databaseConn.addTaRound2GradingAssignment(assignId, criteriaId, taId, group, nextIdx, nextIdx - taToOffset.get(taId));
                    nextIdx++;
                }
                taToNextIdx.put(taId, nextIdx);
            }
        }
    }

    private static void loadRoster(String pathToRosterFile) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .build();

        try (Reader reader = new FileReader(pathToRosterFile);
             CSVParser parser = new CSVParser(reader, format)) {

            List<String> newUserIds = new ArrayList<>();
            int skipped = 0;
            for (CSVRecord record : parser) {
                if (record.size() > 0 && record.get(0).startsWith("Information")) {
                    continue;
                }
                if (record.size() < 4) {
                    System.err.printf("Roster line %d: expected 4 columns, got %d -- skipping%n",
                            record.getRecordNumber(), record.size());
                    continue;
                }

                String lastName = record.get(0).trim();
                String firstName = record.get(1).trim();
                String userId = record.get(2).trim();
                String role = record.get(3).trim();

                if (userId.isEmpty()) {
                    System.err.printf("Roster line %d: empty userId -- skipping%n",
                            record.getRecordNumber());
                    continue;
                }

                String studentName = firstName + " " + lastName;
                boolean inserted;
                if (role.equals("student")) {
                    inserted = databaseConn.insertStudentRecord(userId, studentName);
                    if (inserted) {
                        newUserIds.add(userId);
                    } else {
                        skipped++;
                    }
                } else {
                    inserted = databaseConn.insertInstructorRecord(userId, role, studentName);
                    if (!inserted) skipped++;
                }
            }

            if (skipped > 0) {
                System.out.println(skipped + " existing record(s) skipped (already in DB). Re-run with --overwrite to replace the entire roster.");
            }

            // Assign anonymous IDs only to newly inserted students.
            // Existing students keep their previously assigned anon IDs.
            Random rand = new Random();
            int idx = 0;
            while (!newUserIds.isEmpty()) {
                idx++;
                String potentialName = "student" + (idx < 10 ? "0" : "") + idx;
                if (newUserIds.contains(potentialName)) {
                    continue;
                }
                int userIdx = rand.nextInt(newUserIds.size());
                String userId = newUserIds.get(userIdx);
                newUserIds.remove(userIdx);
                databaseConn.updateStudentAnonId(userId, potentialName);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private static void loadGroups(String pathToGroupFile, boolean overwrite) {
        try {
            if (databaseConn.groupsExist()) {
                if (!overwrite) {
                    System.out.println("Groups are already loaded in this database.");
                    System.out.println("Re-run with --overwrite to replace them.");
                    return;
                }
                System.out.println("Clearing existing group data...");
                databaseConn.clearGroupData();
            }

            FileReader reader = new FileReader(pathToGroupFile);

            // Parse the JSON file
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(reader);

            // Get original group names
            List<String> originalGroups = new ArrayList<>();
            for (String groupId : (Iterable<String>) jsonObject.keySet()) {
                originalGroups.add(groupId.toLowerCase());
            }

            // Randomly assign new group names
            Map<String, String> originalToAnonGroups = new HashMap<>();
            Random rand = new Random();
            int idx = 0;
            while (!originalGroups.isEmpty()) {
                idx++;
                String potentialName = "group" + (idx < 10 ? "0" : "") + idx;
                if (originalGroups.contains(potentialName)) {
                    continue;
                }
                int origGroupIndex = rand.nextInt(originalGroups.size());
                String origGroupId = originalGroups.get(origGroupIndex);
                originalToAnonGroups.put(origGroupId, potentialName);
                originalGroups.remove(origGroupIndex);

                // Populate group correspondence table
                databaseConn.insertGroupCorrespondenceRecord(origGroupId, potentialName);
            }

            // Use new group names in student table
            for (Map.Entry<String, JSONArray> entry : (Iterable<Map.Entry<String, JSONArray>>) jsonObject.entrySet()) {
                String groupId = entry.getKey();
                JSONArray userIds = entry.getValue();

                // Store user IDs mapped to anonymized group IDs
                for (Object userId : userIds) {
                    databaseConn.updateStudentGroup(userId.toString(), groupId.toLowerCase());
                }
            }

            reader.close();
        } catch (IOException | org.json.simple.parser.ParseException e) {
            e.printStackTrace();
        }
    }

    private static void createAssignment(String assignmentName, String studentSubmissionDdl, String pathToCriteriaFile, String pathToSubmissionsDirectory) throws IOException, org.json.simple.parser.ParseException, SQLException {
        Random random = new Random();
        int assignmentId = random.nextInt(1000000);

        JSONParser parser = new JSONParser();

        // Parse JSON file
        Object obj = parser.parse(new FileReader(pathToCriteriaFile));
        JSONObject jsonObject = (JSONObject) obj;

        // Reorder criteria so built-ins appear in the follow order:
        // (Encapsulation, Modularity, Decoupling, Clarity, Robustness) before any custom ones.
        // The returned permutation maps new-index -> old-index and is used below to realign
        // training answers/feedback, which are positionally indexed against the criteria array.
        int[] criteriaPermutation = reorderCriteriaWithBuiltinsFirst((JSONArray) jsonObject.get("criteria"));

        // Fail if the submissions directory has no valid subdirectories.
        File submissionsDir = new File(pathToSubmissionsDirectory);
        File[] subDirs = submissionsDir.listFiles(File::isDirectory);
        if (subDirs == null || subDirs.length == 0) {
            System.err.println("[31mNo submission subdirectories found in: " + pathToSubmissionsDirectory
                    + ". Please check your input path and rerun the command.[0m");
            return;
        }

        // creating Training Submission Table and updating Training table
        if (jsonObject.get("training") != null) {
            databaseConn.createEvalSubmissionTable(assignmentId, new HashMap<>(), 3);
            // Extracting Training information
            JSONArray trainingArray = (JSONArray) jsonObject.get("training");
            List<String> sub1Paths = new ArrayList<>();
            List<String> sub2Paths = new ArrayList<>();
            List<String> answersList = new ArrayList<>();
            List<String> feedbacksList = new ArrayList<>();
            for (int i = 0; i < trainingArray.size(); i++) {
                JSONObject trainingObject = (JSONObject) trainingArray.get(i);
                sub1Paths.add("\"" + new File(trainingObject.get("program1").toString()).getAbsolutePath() + "\"");
                sub2Paths.add("\"" + new File(trainingObject.get("program2").toString()).getAbsolutePath() + "\"");
                answersList.add(permuteJsonArray((JSONArray) trainingObject.get("answers"), criteriaPermutation).toString());
                feedbacksList.add(permuteJsonArray((JSONArray) trainingObject.get("feedback"), criteriaPermutation).toString());
            }
            databaseConn.insertTrainingRecord(assignmentId, sub1Paths.toString(), sub2Paths.toString(), answersList.toString(), feedbacksList.toString());
        }

        int pairsPerStudent = Integer.parseInt(jsonObject.get("pairsPerStudent").toString());
        int evalsPerTaPerCriteria = Integer.parseInt(jsonObject.get("evalsPerTaPerCriteria").toString());

        databaseConn.insertAssignmentRecord(assignmentId, assignmentName, studentSubmissionDdl, pairsPerStudent, evalsPerTaPerCriteria);

        // Extract assignment description
        String assignmentDescription = (String) jsonObject.get("id");
        String uniqueId = assignmentId + "-" + assignmentDescription;

        // Extract peer eval criteria
        Map<Integer, Integer> freeToMaxCharPeer = new HashMap<>();
        JSONArray criteriaArray = (JSONArray) jsonObject.get("criteria");
        JSONArray freeResponseArray = (JSONArray) jsonObject.get("freeResponse");
        List<Integer> sharedIds1 = insertCriteria(criteriaArray, false, 0, assignmentId, uniqueId, 1, freeToMaxCharPeer);
        insertCriteria(freeResponseArray, true, 0, assignmentId, uniqueId, 1 + criteriaArray.size(), freeToMaxCharPeer);
        System.out.println("sharedIds1: " + sharedIds1);
        databaseConn.createEvalSubmissionTable(assignmentId, freeToMaxCharPeer, 0);

        // Extract self eval criteria (MC-only: selfCriteria + shared criteria; no freeResponse section)
        Map<Integer, Integer> freeToMaxCharSelf = new HashMap<>();
        JSONArray selfCriteriaArray = (JSONArray) jsonObject.get("selfCriteria");
        JSONArray sharedCriteriaArray = (JSONArray) jsonObject.get("criteria");
        selfCriteriaArray.addAll(sharedCriteriaArray);
        List<Integer> sharedIds2 = insertCriteria(selfCriteriaArray, false, 1, assignmentId, uniqueId, criteriaArray.size() + freeResponseArray.size() + 1, freeToMaxCharSelf);
        databaseConn.createEvalSubmissionTable(assignmentId, freeToMaxCharSelf, 1);

        //Extract instructor eval criteria (MC-only)
        Map<Integer, Integer> freeToMaxCharInstr = new HashMap<>();
        JSONArray InstrCriteriaArray = (JSONArray) jsonObject.get("criteria");
        List<Integer> sharedIds3 = insertCriteria(InstrCriteriaArray, false, 2, assignmentId, uniqueId, criteriaArray.size() + freeResponseArray.size() + selfCriteriaArray.size() + 1, freeToMaxCharInstr);
        databaseConn.createEvalSubmissionTable(assignmentId, freeToMaxCharSelf, 2);

        for (int sharedIdIdx = 0; sharedIdIdx < sharedIds1.size(); sharedIdIdx++) {
            databaseConn.insertCriteriaCorrespondenceRecord(assignmentId, sharedIds3.get(sharedIdIdx), sharedIds1.get(sharedIdIdx), sharedIds2.get(sharedIdIdx));
        }

        // Extract the programming language
        String language = (String) jsonObject.get("language");

        // Extract the "level" of code coverage to be used
        Long coverageLevel = (Long) jsonObject.get("coverageLevel");

        System.out.println("Coverage level: " + coverageLevel);
        System.out.println("Language selected: " + language);

        DirectoryStream<Path> directoryStream = Files.newDirectoryStream(Paths.get(pathToSubmissionsDirectory));
        for (Path path : directoryStream) {
            if (Files.isDirectory(path)) {
                // The path can be anything like /.../student_submissions/<assignment>-<project-nummber><group>/coverage-after/...
                String subdirectoryName = path.getFileName().toString();
                String subPath = path.toString();
                String[] subParts = subdirectoryName.split("-");
                String groupId = subParts[1].toLowerCase();

                String subId = assignmentId + "-" + groupId;

                List<String[]> commands = new ArrayList<>();
                Language lang = Language.fromString(language);
                // coverageLevel: 0 = none, 1 = parse existing XML only
                switch (lang) {
                    case GOLANG -> {
                        switch (coverageLevel.intValue()) {
                            case 1 -> Parse.parseFiles(lang, subPath, Parse.COVERAGE_AFTER_DIR);
                        }
                    }
                    case TYPESCRIPT -> {
                        switch(coverageLevel.intValue()) {
                            case 1 -> Parse.parseFiles(lang, subPath, Parse.COVERAGE_AFTER_DIR);
                        }
                    }
                }
                String submissionPath = Paths.get(subPath).toAbsolutePath().toString();
                if (coverageLevel.intValue() != 0) {
                    submissionPath += File.separator + Parse.COVERAGE_AFTER_DIR;
                }
                databaseConn.insertSubmissionRecord(subId, assignmentId, assignmentName, groupId, submissionPath);

                System.out.println("Successfully created submission entry for group " + groupId);
            }
        }
        // Snapshot each student's current group into student_assignment_group for this assignment,
        // so peer-eval group lookups remain correct when multiple assignments share the same DB.
        Map<String, String> studentToGroup = databaseConn.getStudentToGroupMap();
        for (Map.Entry<String, String> entry : studentToGroup.entrySet()) {
            databaseConn.insertStudentAssignmentGroupRecord(entry.getKey(), assignmentId, entry.getValue());
        }
        System.out.println("Snapshotted " + studentToGroup.size() + " student-group mappings for assignment " + assignmentId);
    }

    // Built-in criteria order: Encapsulation, Modularity, Decoupling, Clarity, Robustness
    private static final List<String> BUILTIN_CRITERIA_ORDER =
            List.of("encapsulation", "modularity", "decoupling", "clarity", "robustness");

    /*
     * Returns the index of the built-in criterion that the given criterion is a variant of,
     * or Integer.MAX_VALUE if the criterion is not a built-in.
     */
    private static int criteriaBuiltinIndex(JSONObject criterion) {
        Object nameObj = criterion.get("name");
        if (nameObj == null) return Integer.MAX_VALUE;
        // get the name of the criterion without the HTML tags
        String stripped = nameObj.toString().replaceAll("<[^>]+>", "").trim();
        int colonIdx = stripped.indexOf(':');
        String namePart = (colonIdx >= 0 ? stripped.substring(0, colonIdx) : stripped).toLowerCase();
        for (int i = 0; i < BUILTIN_CRITERIA_ORDER.size(); i++) {
            if (namePart.contains(BUILTIN_CRITERIA_ORDER.get(i))) return i;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Reorders criteria in-place so that built-in criteria appear in the
     * specified order (Encapsulation -> Modularity -> Decoupling -> Clarity -> Robustness)
     * followed by any custom criteria in their original relative order.
     *
     * @return permutation where permutation[newIndex] = oldIndex
     */
    private static int[] reorderCriteriaWithBuiltinsFirst(JSONArray criteria) {
        int n = criteria.size();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, Comparator.comparingInt(i -> criteriaBuiltinIndex((JSONObject) criteria.get(i))));
        List<Object> sorted = new ArrayList<>(n);
        for (int newIdx : indices) sorted.add(criteria.get(newIdx));
        criteria.clear();
        criteria.addAll(sorted);
        int[] permutation = new int[n];
        for (int i = 0; i < n; i++) permutation[i] = indices[i];
        return permutation;
    }

    /**
     * Returns a new JSONArray whose elements are source reordered by permutation
     * (permutation[newIndex] = oldIndex). If the lengths differ the original array is returned as-is.
     */
    private static JSONArray permuteJsonArray(JSONArray source, int[] permutation) {
        if (source == null || source.size() != permutation.length) return source;
        JSONArray result = new JSONArray();
        for (int newIdx = 0; newIdx < permutation.length; newIdx++) {
            result.add(source.get(permutation[newIdx]));
        }
        return result;
    }

    private static List<Integer> insertCriteria(JSONArray criteriaArray, boolean isFreeResponse, int evalType, int assignmentId, String uniqueId, int startIndex, Map<Integer, Integer> freeToMaxChar) {
        // Criteria to TA mapping
        List<Integer> sharedIds = new ArrayList<>();
        Map<Integer, List<String>> criteriaIdToTa = new HashMap<>();
        for (int i = startIndex; i < startIndex + criteriaArray.size(); i++) {
            String uniqueCriteria = uniqueId;
            JSONObject criteriaObject = (JSONObject) criteriaArray.get(i - startIndex);
            int criteriaId = i;
            uniqueCriteria = uniqueCriteria + "-" + criteriaId;

            String criteriaText;
            // creating the text for shared criteria
            if (criteriaObject.get("text") == null) {
                sharedIds.add(i);
                String nameText = (String) criteriaObject.get("name");
                String additionalText;
                String postText = (String) criteriaObject.get("postText");
                if (evalType == 0){
                    additionalText = (String) criteriaObject.get("peerPreText");
                    criteriaText = nameText + ": " + additionalText + " " + postText;
                } else if (evalType == 1) {
                    additionalText = (String) criteriaObject.get("selfPreText");
                    criteriaText = nameText + ": " + additionalText + " " + postText;
                } else {
                    // instructor
                    additionalText = ""; //((String) criteriaObject.get("selfPreText")).replace("your","this");
                    criteriaText = nameText; // + ": " + additionalText;
                    if (criteriaObject.containsKey("postTextTa")) {
                        criteriaText += criteriaObject.get("postTextTa").toString();
                    }
                    List<String> tas = new ArrayList<>();
                    for (Object ta: (JSONArray) criteriaObject.get("tas")){
                        tas.add(ta.toString());
                    }
                    criteriaIdToTa.put(criteriaId, tas);

                    // Adding the ta Rubric
                    JSONArray taRubric = (JSONArray) criteriaObject.get("taRubric");
                    String header = "";
                    List<String> deductionText = new ArrayList<>();
                    List<Double> deductionPts = new ArrayList<>();
                    int rubricID = 0;
                    boolean isMutuallyExclusive = false;
                    for (int j = 0; j < taRubric.size(); j++){
                        String newUniqueCriteria = uniqueCriteria + "-";
                        JSONObject rubricElement = (JSONObject) taRubric.get(j);
                        if (rubricElement.get("isHeader").equals(true)){
                            header = rubricElement.get("text").toString();
                            isMutuallyExclusive = (Boolean) rubricElement.get("mutuallyExclusive");
                        } else {
                            deductionText.add("[" + "\"" + rubricElement.get("text").toString() + "\"" + "]");
                            deductionPts.add(((Number) rubricElement.get("pts")).doubleValue());
                            if (j+1 >= taRubric.size() || ((JSONObject) taRubric.get(j+1)).get("isHeader").equals(true)){
                                newUniqueCriteria = newUniqueCriteria + rubricID;
                                databaseConn.insertRubricRecord(newUniqueCriteria, assignmentId, criteriaId, header, deductionText.toString(), deductionPts.toString(), isMutuallyExclusive);
                                deductionText.clear();
                                deductionPts.clear();
                                rubricID ++;
                            }
                        }
                    }
                }
            } else {
                criteriaText = (String) criteriaObject.get("text");
            }
            // isFreeResponse is determined by which config array this entry came from, not a per-entry flag.
            int maxChar = isFreeResponse ? ((Number) criteriaObject.get("maxChar")).intValue() : 0;
            if (isFreeResponse) {
                freeToMaxChar.put(criteriaId, maxChar);
            }
            double weight = ((Number) criteriaObject.get("weight")).doubleValue();
            databaseConn.insertCriteriaRecord(uniqueCriteria, assignmentId, criteriaId, criteriaText, isFreeResponse, evalType, maxChar, weight);
        }
        // reversing the criteria mapping
        Map<String, List<Integer>> tasToCriteriaIds = new HashMap<>();
        for (Integer criteriaId: criteriaIdToTa.keySet()){
            for (String ta: criteriaIdToTa.get(criteriaId)){
                if (tasToCriteriaIds.containsKey(ta)){
                    tasToCriteriaIds.get(ta).add(criteriaId);
                }else{
                    List<Integer> criteriaList = new ArrayList<>();
                    criteriaList.add(criteriaId);
                    tasToCriteriaIds.put(ta, criteriaList);
                }
            }
        }
        for (String ta: tasToCriteriaIds.keySet()){
            databaseConn.setInstructorCriteriaForAssignment(ta, assignmentId, tasToCriteriaIds.get(ta));
        }
        return sharedIds;
    }

    // the grading algorithm
    private static void loadPeerMC(int assignId, String path, Map<String, Double> sIdToWeight) throws IOException, SQLException {
        // what will eventually be returned
        // get questions with their peer evaluations
        Map<Integer, List<PeerEval>> questionScores = databaseConn.dataLoadPeerMC(assignId);
        // get list of groupIDs who submitted homework
        List<String> groupIDs = databaseConn.getGroupIdsForAssignment(assignId);
        // If the mapping to sIds to weight is empty, just set all weights to 1
        if(sIdToWeight.isEmpty()){
            List<String> sIds = databaseConn.getStudentUserIds();
            for(String sId: sIds){
                sIdToWeight.put(sId, 1.0);
            }
        }
        Map<Integer, Map<String, Double>> rankings = computeEloRatings(questionScores, groupIDs, sIdToWeight);
        outputEloRankingCsv(rankings, path);
    }


    private static void loadSelfMC(int assignID, String path) throws IOException {
        // Extract data
        Map<String, SelfEval> selfEvals = databaseConn.dataLoadSelfMC(assignID);

        // Construct header
        StringBuilder sb = new StringBuilder();
        sb.append("UserId");
        List<Integer> criteriaIDs = new ArrayList<>(selfEvals.values()).get(0).getCriteriaIDs();
        for (int criteriaID : criteriaIDs) {
            sb.append(",").append(criteriaID).append("_before").append(",").append(criteriaID).append("_after");
        }

        // Insert rows
        for (Map.Entry<String, SelfEval> eval : selfEvals.entrySet()) {
            sb.append("\n").append(eval.getKey()).append(",").append(eval.getValue());
        }

        // Write data to file
        FileWriter fw = new FileWriter(path);
        fw.write(sb.toString());
        fw.close();
    }
    private static void loadInstructorMC(int assignID, String path) throws IOException {
        // what will eventually be returned
        // get questions with their peer evaluations
        Map<Integer, Set<InstructorEval>> questionScores = databaseConn.dataLoadInstructorMC(assignID);
        System.out.println("questionScores: " + questionScores);
    }

    public static Map<Integer, Map<String, Double>> computeEloRatings(Map<Integer, List<PeerEval>> peerEvals, List<String> groupIDs, Map<String, Double> sIdToWeight) {
        Map<Integer, Map<String, Double>> scoreMap = new HashMap<>();

        // setting up scoreMap:
        // looping through questionScores to add criteriaID keys to scoreMap
        for (Map.Entry<Integer, List<PeerEval>> criteriaEval : peerEvals.entrySet()) {
            scoreMap.put(criteriaEval.getKey(), new HashMap<>());
        }
        // looping through scoreMap to add key groupID and initial value 1600
        for (Map.Entry<Integer, Map<String, Double>> criteriaInnerMap : scoreMap.entrySet()) {
            for (String groupID : groupIDs) {
                criteriaInnerMap.getValue().put(groupID, 1600.0);
            }
        }

        // start of elos algorithm:
        for (Map.Entry<Integer, List<PeerEval>> criteriaEval : peerEvals.entrySet()) {
            List<PeerEval> peerEvalsList = criteriaEval.getValue();
            for (PeerEval peerEval : peerEvalsList) {
                String group1 = peerEval.getGroupId();
                String group2 = peerEval.getOtherGroupId();
                String sId = peerEval.getGraderId();

                Double rating1 = scoreMap.get(criteriaEval.getKey()).get(group1);
                Double rating2 = scoreMap.get(criteriaEval.getKey()).get(group2);

                double expScore1 = 1/(1+Math.pow(10, ((rating2 - rating1)/400.0)));
                double expScore2 = 1/(1+Math.pow(10, ((rating1 - rating2)/400.0)));

                int questionScore = peerEval.getScore();
                double actScore1;
                double actScore2;

                if (questionScore == 3) {
                    // group 1 won
                    actScore1 = 1;
                    actScore2 = 0;
                } else if (questionScore == 2) {
                    // group 1 slightly won
                    actScore1 = 1;
                    actScore2 = 0;
                } else if (questionScore == 1) {
                    // group 2 slightly won
                    actScore1 = 0;
                    actScore2 = 1;
                } else {
                    // group 2 won
                    actScore1 = 0;
                    actScore2 = 1;
                }

                // multiply k factor by student reliability weight
                double kFactor = 32.0 * sIdToWeight.getOrDefault(sId, 1.0);
                Double newRating1 = rating1 + kFactor * (actScore1 - expScore1);
                Double newRating2 = rating2 + kFactor * (actScore2 - expScore2);

                // update new ratings
                scoreMap.get(criteriaEval.getKey()).put(group1, newRating1);
                scoreMap.get(criteriaEval.getKey()).put(group2, newRating2);
            }
        }
        return scoreMap;
    }

    public static Map<String, Double> computeStudentWeights(Map<String, Map<Integer, List<Integer>>> allTraining, List<List<Integer>> answers) {
        Map<String, Double> weights = new HashMap<>();

        for (Map.Entry<String, Map<Integer, List<Integer>>> entry : allTraining.entrySet()) {
            String sid = entry.getKey();
            double totalPoints = 0.0;
            double earnedPoints = 0.0;

            for (Map.Entry<Integer, List<Integer>> t : entry.getValue().entrySet()) {
                Integer trainingIdx = t.getKey();
                List<Integer> studentAns = t.getValue();
                if (trainingIdx >= answers.size()) {
                    continue;
                }
                List<Integer> correct = answers.get(trainingIdx);
                if (studentAns.size() != correct.size()) {
                    continue;
                }

                totalPoints += correct.size();
                for (int i = 0; i < correct.size(); i++) {
                    int diff = Math.abs(studentAns.get(i) - correct.get(i));
                    if (diff == 0) {
                        earnedPoints += 1.0;
                    } else if (diff == 1) {
                        earnedPoints += 0.5;
                    }
                }
            }

            double w = totalPoints > 0 ? (earnedPoints / totalPoints) : 0.0;
            weights.put(sid, w);
        }

        return weights;
    }

    public static Map<String, Double> binaryAccurateWeights(Map<String, Double> weights, double threshold) {
        // Interpret `threshold` as the fraction (0.0-1.0) of worst students to remove.
        int n = weights.size();
        int numToRemove = (int)Math.floor(n * threshold);

        // Sort students by weight ascending (worst first)
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());
        entries.sort((a, b) -> {
            int c = Double.compare(a.getValue(), b.getValue());
            if (c != 0) return c;
            return a.getKey().compareTo(b.getKey());
        });

        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String sid = entries.get(i).getKey();
            if (i < numToRemove) {
                out.put(sid, 0.0);
            }
        }
        return out;
    }

    /**
     * Computes group Elo Rankings with student reliability from the training data
     * @param assignId assignment ID
     * @param  trainingIdx the training that is influencing the weights
     * @param threshold the MSE threshold, where MSE above that threshold have a different alg. weight
     * @param altWeight the alternative algorithm weight
     * @param path path to
     */
    private static void ratingsWithReliability(int assignId, int trainingIdx, double threshold, double altWeight, String path) throws SQLException, IOException {
        // peer evaluation responses
        Map<Integer, List<PeerEval>> questionScores = databaseConn.dataLoadPeerMC(assignId);
        // training responses for the final training
        Map<String, List<Integer>> trainingScores = databaseConn.dataLoadTrainingMC(assignId, trainingIdx);
        // training answers
        List<Integer> answers =  databaseConn.getAnswers(assignId).get(trainingIdx);
        // get list of groupIDs who submitted homework
        List<String> groupIDs = databaseConn.getGroupIdsForAssignment(assignId);
        Map<String, Double> sIdToWeight = new HashMap<>();
        // calculating the MSE
        for (String sId: trainingScores.keySet()){
            Double mse = 0.0;
            List<Integer> choices = trainingScores.get(sId);
            for (int i = 0; i< choices.size(); i++){
                mse += Math.pow((choices.get(i) - answers.get(i)), 2);
            }
            mse = mse/choices.size();
            // If the error is within the threshold, student contributes full weight
            if(mse < threshold){
                sIdToWeight.put(sId, 1.0);
            } else{
                sIdToWeight.put(sId, altWeight);
            }
        }
        loadPeerMC(assignId, path, sIdToWeight);
    }

    public static void outputEloRankingCsv(Map<Integer, Map<String, Double>> rankings, String path) throws IOException {
        // Rearrange data & build header
        Map<String, List<Double>> flippedRankings = new HashMap<>();
        for (String group : rankings.get(1).keySet()) {
            flippedRankings.put(group, new ArrayList<>());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Group,");
        for (int criteriaID = 1; criteriaID <= Collections.max(rankings.keySet()); criteriaID++) {
            sb.append(criteriaID).append(",");
            Map<String, Double> innerMap = rankings.get(criteriaID);
            for (String group : innerMap.keySet()) {
                flippedRankings.get(group).add(innerMap.get(group));
            }
        }

        // Put data in csv format
        sb.deleteCharAt(sb.length() - 1);
        for (Map.Entry<String, List<Double>> entry : flippedRankings.entrySet()) {
            String group = entry.getKey();
            List<Double> scores = entry.getValue();
            sb.append("\n").append(group).append(",");
            for (int criteriaID = 0; criteriaID < scores.size(); criteriaID++) {
                sb.append(scores.get(criteriaID)).append(",");
            }
            sb.deleteCharAt(sb.length() - 1);
        }

        // Write data to file
        FileWriter fw = new FileWriter(path);
        System.out.println(sb.toString());
        fw.write(sb.toString());
        fw.close();
    }

    private static void loadPeerFR(int assignId, String pathToFile) throws SQLException, ParseException {
        Map<String, Map<Integer, List<String>>> mapRepFr = databaseConn.dataLoadPeerFr(assignId);
        outputFreeResponseJson(mapRepFr, pathToFile, assignId);
    }

    private static void loadSelfFR(int assignId, String pathToFile) throws SQLException {
        Map<String, Map<Integer, List<String>>> mapSelfFr = databaseConn.dataLoadSelfFr(assignId);
        outputFreeResponseJson(mapSelfFr, pathToFile, assignId);
    }

    private static void outputFreeResponseJson(Map<String, Map<Integer, List<String>>> mapRepFr, String pathToFile, int assignId) throws SQLException {
        JSONObject resultJson = new JSONObject();
        for (Map.Entry<String, Map<Integer, List<String>>> groupEntry : mapRepFr.entrySet()) {
            String groupId = groupEntry.getKey();
            Map<Integer, List<String>> criteriaMap = groupEntry.getValue();

            JSONObject criteriaJson = new JSONObject();

            for (Map.Entry<Integer, List<String>> criteriaEntry : criteriaMap.entrySet()) {
                int criteriaId = criteriaEntry.getKey();
                List<String> responses = criteriaEntry.getValue();
                String criteriaText = databaseConn.getCriteriaTextFromTable(criteriaId, assignId);
                criteriaJson.put(criteriaText, responses);
            }

            resultJson.put(groupId, criteriaJson);
        }
        try {
            FileWriter fileWriter = new FileWriter(pathToFile);
            ObjectMapper mapper = new ObjectMapper();
            Object js = mapper.readValue(resultJson.toJSONString(), Object.class);
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(js);
            fileWriter.write(prettyJson);
            fileWriter.flush();
            fileWriter.close();
            System.out.println("JSON data has been written to the file: " + pathToFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadPeerEvalCompletion(int assignID, String path) throws IOException, SQLException {
        Map<String, Double> completion = databaseConn.loadPeerEvalCompletion();
        outputPeerEvalCsv(completion, path);
    }

    public static void outputPeerEvalCsv(Map<String, Double> completion, String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("UserId,Percent Completion");
        for (Map.Entry<String, Double> entry : completion.entrySet()) {
            sb.append("\n").append(entry.getKey()).append(",").append((entry.getValue() * 100));
        }

        // Write data to file
        FileWriter fw = new FileWriter(path);
        System.out.println(sb.toString());
        fw.write(sb.toString());
        fw.close();
    }
}
