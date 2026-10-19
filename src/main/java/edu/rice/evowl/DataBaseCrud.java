package edu.rice.evowl;

import java.sql.*;
import java.util.*;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Wraps all SQL queries and mutations used by the CLI commands.
 *
 * Call connect before any other method and closeConnection when done.
 * createAllTables must be called once per fresh database.
 */
public class DataBaseCrud {
    Connection connection;

    private int expNumPeerEvals = 5;
    private int expNumSelfEvals = 2;
    private String dbName;

    public DataBaseCrud(String dbName) {
        this.dbName = dbName;
    }

    /*connect method will create connection with database using Connection Interface and
      Driver manager for registering driver for specific database*/
    public void connect(String dbUrl, String userName, String password) throws SQLException {
        connection = DriverManager.getConnection(dbUrl, userName, password);
    }


    /**
     * Inserts a student record if the userId does not already exist.
     * @return true if a new row was inserted, false if the student already existed
     */
    public boolean insertStudentRecord(String userId, String userName) {
        String sql = "INSERT IGNORE INTO student (user_id, user_name) VALUES (?, ?)";
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, userId);
            statement.setString(2, userName);
            return statement.executeUpdate() > 0;
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e.toString());
            return false;
        }
    }

    /**
     * Inserts an instructor record if the userId does not already exist.
     * @return true if a new row was inserted, false if the instructor already existed
     */
    public boolean insertInstructorRecord(String userId, String role, String userName) {
        if (!role.equals("professor") && !role.equals("ta")) {
            throw new RuntimeException("Invalid role: " + "'" + role + "'");
        }
        String sql = "INSERT IGNORE INTO instructor (user_id, role, user_name) VALUES (?, ?, ?)";
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, userId);
            statement.setString(2, role);
            statement.setString(3, userName);
            return statement.executeUpdate() > 0;
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e.toString());
            return false;
        }
    }


    public void insertAssignmentRecord(int id, String name, String studentSubmissionDdl, int pairsPerStudent, int evalsPerTaPerCriteria) {
        String sql = "INSERT INTO assignment (assignment_id, assignment_name, student_submission_ddl, pairs_per_student, evals_per_ta_per_criteria) VALUES (?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.setTimestamp(3, Timestamp.valueOf(studentSubmissionDdl));
            statement.setInt(4, pairsPerStudent);
            statement.setInt(5, evalsPerTaPerCriteria);
            statement.executeUpdate();
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e.toString());
        }
    }

    /**
     * Returns all assignments in the database as a list of [id, name, deadline] arrays.
     * @return all assignments in the database as a list of [id, name, deadline] arrays
     */
    public List<String[]> listAssignments() {
        List<String[]> results = new ArrayList<>();
        String sql = "SELECT assignment_id, assignment_name, student_submission_ddl FROM assignment ORDER BY assignment_id";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                results.add(new String[]{
                    String.valueOf(rs.getInt("assignment_id")),
                    rs.getString("assignment_name"),
                    String.valueOf(rs.getTimestamp("student_submission_ddl"))
                });
            }
        } catch (SQLException e) {
            System.out.println("Error listing assignments: " + e.getMessage());
        }
        return results;
    }

    /**
     * Returns true if an assignment with the given name already exists in the database.
     * @param name the assignment name to check
     * @return true if an assignment with the given name already exists in the database
     */
    public boolean assignmentExistsByName(String name) {
        String sql = "SELECT 1 FROM assignment WHERE assignment_name = ? LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Returns the assignment_id for the given assignment name, or -1 if not found.
     */
    public int getAssignmentIdByName(String name) {
        String sql = "SELECT assignment_id FROM assignment WHERE assignment_name = ? LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getInt("assignment_id");
        } catch (SQLException e) {
            System.out.println("Exception in getAssignmentIdByName: " + e);
        }
        return -1;
    }

    /**
     * Deletes all data associated with the given assignment_id.
     */
    public void deleteAssignmentCascade(int assignmentId) {
        String[] dropTables = {
            "peer_eval_submission_" + assignmentId,
            "self_eval_submission_" + assignmentId,
            "instructor_eval_submission_" + assignmentId,
            "training_submission_" + assignmentId
        };
        try (Statement stmt = connection.createStatement()) {
            for (String table : dropTables) {
                stmt.executeUpdate("DROP TABLE IF EXISTS `" + table + "`");
                System.out.println("Dropped table " + table);
            }
        } catch (SQLException e) {
            System.out.println("Exception dropping dynamic tables for assignment " + assignmentId + ": " + e);
        }

        String[][] deletes = {
            {"DELETE FROM submission WHERE assignment_id = ?"},
            {"DELETE FROM peer_eval_assignment WHERE assignment_id = ?"},
            {"DELETE FROM self_eval_assignment WHERE assignment_id = ?"},
            {"DELETE FROM instructor_eval_assignment WHERE assignment_id = ?"},
            {"DELETE FROM training_assignment WHERE assignment_id = ?"},
            {"DELETE FROM training WHERE assignment_id = ?"},
            {"DELETE FROM criteria WHERE assignment_id = ?"},
            {"DELETE FROM rubric WHERE assignment_id = ?"},
            {"DELETE FROM criteria_correspondence WHERE assign_id = ?"},
            {"DELETE FROM instructor_criteria_assignment WHERE assign_id = ?"},
            {"DELETE FROM student_assignment_group WHERE assignment_id = ?"},
            {"DELETE FROM assignment WHERE assignment_id = ?"},
        };
        try {
            connection.setAutoCommit(false);
            for (String[] row : deletes) {
                try (PreparedStatement stmt = connection.prepareStatement(row[0])) {
                    stmt.setInt(1, assignmentId);
                    stmt.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            System.out.println("Exception during cascade delete for assignment " + assignmentId + ": " + e);
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
        System.out.println("Cascade-deleted all data for assignment_id=" + assignmentId);
    }

    public void updateStudentGroup(String userId, String groupId){
        String sql = "UPDATE student SET group_id=? WHERE user_id=?";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, groupId);
            statement.setString(2, userId);
            statement.executeUpdate();
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e.toString());
        }
    }

    public void updateStudentAnonId(String userId, String anonId){
        String sql = "UPDATE student SET anon_id=? WHERE user_id=?";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, anonId);
            statement.setString(2, userId);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void setInstructorCriteriaForAssignment(String userId, int assignId, List<Integer> criteriaIds) {
        String deleteSql = "DELETE FROM instructor_criteria_assignment WHERE user_id=? AND assign_id=?";
        String insertSql = "INSERT INTO instructor_criteria_assignment (user_id, assign_id, criteria_id) VALUES (?, ?, ?)";

        try {
            PreparedStatement deleteStmt = connection.prepareStatement(deleteSql);
            deleteStmt.setString(1, userId);
            deleteStmt.setInt(2, assignId);
            deleteStmt.executeUpdate();

            PreparedStatement insertStmt = connection.prepareStatement(insertSql);
            for (int criteriaId : criteriaIds) {
                insertStmt.setString(1, userId);
                insertStmt.setInt(2, assignId);
                insertStmt.setInt(3, criteriaId);
                insertStmt.addBatch();
            }
            insertStmt.executeBatch();
        } catch (Exception e) {
            System.out.println("Exception in setInstructorCriteriaForAssignment:\n" + e);
        }
    }

    public void insertStudentAssignmentGroupRecord(String userId, int assignmentId, String groupId) {
        String sql = "INSERT IGNORE INTO student_assignment_group (user_id, assignment_id, group_id) VALUES (?, ?, ?)";
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, userId);
            statement.setInt(2, assignmentId);
            statement.setString(3, groupId);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void closeConnection(){
        try {
            if(connection!=null) {
                connection.close();
            }
        } catch (Exception e) {
            System.out.println("Exception in connection: "+ e.toString());
        }
    }

    public void insertGroupCorrespondenceRecord(String originalId, String anonId) {
        String sql = "INSERT IGNORE INTO `group_correspondence` (group_id, anon_id) VALUES (?, ?)";
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, originalId);
            statement.setString(2, anonId);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void insertCriteriaCorrespondenceRecord(int assignmentId, int taId, int peerId, int selfId) {
        String sql = "INSERT INTO `criteria_correspondence` (unique_id, assign_id, ta_id, peer_id, self_id) VALUES (?, ?, ?, ?, ?)";
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            String uniqueCriteria = assignmentId + "-" + taId;
            statement.setString(1, uniqueCriteria);
            statement.setInt(2, assignmentId);
            statement.setInt(3, taId);
            statement.setInt(4, peerId);
            statement.setInt(5, selfId);
            statement.executeUpdate();
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void insertCriteriaRecord(String uniqueCriteria, int assignmentId, int criteriaId, String criteriaText, boolean isFreeResponse, int evalType, int maxChar, double weight) {
        String sql = "INSERT INTO criteria (unique_id, assignment_id, criteria_id, criteria_text, free_response, eval_type, max_char, weight) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, uniqueCriteria);
            statement.setInt(2, assignmentId);
            statement.setInt(3, criteriaId);
            statement.setString(4, criteriaText);
            statement.setBoolean(5, isFreeResponse);
            statement.setInt(6, evalType);
            statement.setInt(7, maxChar);
            statement.setDouble(8, weight);
            statement.executeUpdate();
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void insertTrainingRecord(int assignmentId, String path1, String path2, String answers, String feedback ){
        String sql = "INSERT INTO training (assignment_id, submission1_paths, submission2_paths, answers, feedback) VALUES (?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setInt(1, assignmentId);
            statement.setString(2, path1);
            statement.setString(3, path2);
            statement.setString(4, answers);
            statement.setString(5, feedback);
            statement.executeUpdate();
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void insertRubricRecord(String uniqueCriteria, int assignmentId, int criteriaId, String headers, String deductionText, String deductionPts, boolean mutuallyExclusive) {
        String sql = "INSERT INTO rubric (unique_id, assignment_id, criteria_id, headers, deduction_text, deduction_pts, mutually_exclusive) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, uniqueCriteria);
            statement.setInt(2, assignmentId);
            statement.setInt(3, criteriaId);
            statement.setString(4, headers);
            statement.setString(5, deductionText);
            statement.setString(6, deductionPts);
            statement.setBoolean(7, mutuallyExclusive);
            statement.executeUpdate();
        } catch(Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void insertSubmissionRecord(String subId, int assignmentId, String assignmentName, String groupId, String pathString) {
        //sql statement for inserting record
        String sql = "INSERT INTO submission (submission_id, assignment_id, assignment_name, group_id, submission_path) VALUES (?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, subId);
            statement.setInt(2, assignmentId);
            statement.setString(3, assignmentName);
            statement.setString(4, groupId);
            statement.setString(5, pathString);
            statement.executeUpdate();

        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e);
        }
    }

    public void addTaRound2GradingAssignment(int assignId, int criteriaId, String taId, String groupId, int nextIdx, int roundIdx) {
        //sql statement for inserting record
        String sql = "INSERT INTO instructor_eval_assignment (unique_id, assignment_id, round, idx, user_id, criteria_id, group_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, taId + "-" + assignId + "-" + nextIdx);
            statement.setInt(2, assignId);
            statement.setInt(3, 1);
            statement.setInt(4, roundIdx);
            statement.setString(5, taId);
            statement.setInt(6, criteriaId);
            statement.setString(7, groupId);
            statement.setBoolean(8, false);
            System.out.println("\t" + statement.toString().substring("com.mysql.cj.jdbc.ClientPreparedStatement: ".length()));
            statement.executeUpdate();
        } catch (Exception e){
            System.out.println("Exception executing " + sql + ":\n" + e);
        }

    }

    public Map<Integer, List<PeerEval>> dataLoadPeerMC(int assignId) {
        Map<Integer, List<PeerEval>> resultMap = new HashMap<>();
        String tableName = "peer_eval_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);

            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                String sub1 = resultSet.getString("submission_id1");
                String sub2 = resultSet.getString("submission_id2");
                String uniqueId = resultSet.getString("unique_id");
                String choices = resultSet.getString("choices");
                String groupId1 = sub1.split("-")[1];
                String groupId2 = sub2.split("-")[1];

                String assignSql = "SELECT user_id FROM peer_eval_assignment WHERE unique_id = ?";
                PreparedStatement assignmentPreparedStatement = connection.prepareStatement(assignSql);
                assignmentPreparedStatement.setString(1, uniqueId);
                ResultSet subResult = assignmentPreparedStatement.executeQuery();
                String graderId = null;
                if (subResult.next()) {
                    graderId = subResult.getString("user_id");
                }

                if (choices.equals("[]")) {
                    continue;
                }
                String[] choicesArray = choices.substring(1, choices.length() - 1).split(", ");
                int[] c = new int[choicesArray.length];
                for (int i = 0; i < choicesArray.length; i++) {
                    c[i] = Integer.parseInt(choicesArray[i]);
                    String query = "SELECT weight FROM criteria WHERE assignment_id = ? AND criteria_id = ?";
                    PreparedStatement preparedStatement = connection.prepareStatement(query);
                    preparedStatement.setInt(1, assignId);
                    preparedStatement.setInt(2, i + 1);

                    ResultSet criteriaSet = preparedStatement.executeQuery();
                    double weight = 0.0;
                    if (criteriaSet.next()) {
                        weight = criteriaSet.getDouble("weight");
                    }

                    PeerEval peerEval = new PeerEval(graderId, groupId1, groupId2, c[i], weight);
                    if (resultMap.containsKey(i+1)) {
                        resultMap.get(i+1).add(peerEval);
                    } else {
                        List<PeerEval> innerList = new ArrayList<>();
                        innerList.add(peerEval);
                        resultMap.put(i+1, innerList);
                    }
                }

            }


        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return resultMap;
    }

    private Map<Integer, List<PeerEval>> getInnerMap(String choices) {
        Map<Integer, List<PeerEval>> innerMap = new HashMap<>();
        String[] choicesArrayString = choices.substring(1, choices.length() - 1).split(", ");
        for (int i = 0; i < choicesArrayString.length; i++) {
            innerMap.put(i + 1, new ArrayList<>());
        }
        return innerMap;
    }

    public Map<String, SelfEval> dataLoadSelfMC(int assignId) {
        Map<String, SelfEval> resultMap = new HashMap<>();

        String tableName = "self_eval_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                String userId = resultSet.getString("submission_id").split("-")[1];
                int uniqueID = Integer.parseInt(resultSet.getString("submission_id").split("-")[2]);
                String choices = resultSet.getString("choices");

                // Find or create the appropriate SelfEval object
                SelfEval eval = null;
                if (!resultMap.containsKey(userId)) {
                    eval = new SelfEval();
                    resultMap.put(userId, eval);
                } else {
                    eval = resultMap.get(userId);
                }

                // Add the data for this self eval
                eval.addSelfEval(choices, getSelfEvalMCCriteriaIDs(assignId), uniqueID);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return resultMap;
    }

    /**
     * Returns the choices made by students for a particular training index
     * @param assignId the assignment ID
     * @param trainingIdx the training index
     * @return a mapping of user IDs to a list of their choices
     */
    public Map<String, List<Integer>> dataLoadTrainingMC(int assignId, int trainingIdx){
        Map<String, List<Integer>> resultMap = new HashMap<>();
        String tableName = "training_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try{
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                String uniqueId = resultSet.getString("unique_id");
                int idx = Integer.parseInt(uniqueId.split("-")[1]);
                // only extracting choices from the 3rd and final training
                if(idx != trainingIdx){
                    continue;
                }
                String choices = resultSet.getString("choices");
                if (choices.equals("[]")) {
                    continue;
                }
                String[] choicesArray = choices.substring(1, choices.length() - 2).split(", ");
                List<Integer> c = new ArrayList<>();
                for (String s : choicesArray) {
                    c.add(Integer.parseInt(s));
                }
                resultMap.put(uniqueId.split("-")[0], c);
            }

        } catch (SQLException e){
            throw new RuntimeException(e);
        }
        return resultMap;
    }

    /**
     * Returns the choices made by students for all training
     * @param assignId the assignment ID
     * @return a mapping of user IDs to mappings of trainingIdxs to a list of their choices
     */
    public Map<String, Map<Integer, List<Integer>>> dataLoadAllTrainingMC(int assignId){
        Map<String, Map<Integer, List<Integer>>> resultMap = new HashMap<>();
        String tableName = "training_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try{
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                String uniqueId = resultSet.getString("unique_id");
                int trainingIdx = Integer.parseInt(uniqueId.split("-")[1]);

                String choices = resultSet.getString("choices");
                if (choices.equals("[]")) {
                    continue;
                }
                String[] choicesArray = choices.substring(1, choices.length() - 1).split(", ");
                List<Integer> c = new ArrayList<>();
                for (String s : choicesArray) {
                    c.add(Integer.parseInt(s));
                }

                String userId = uniqueId.split("-")[0];
                Map<Integer, List<Integer>> studentMap = resultMap.get(userId);
                if (studentMap == null) {
                    studentMap = new HashMap<Integer, List<Integer>>();
                    resultMap.put(userId, studentMap);
                }
                studentMap.put(trainingIdx, c);
            }

        } catch (SQLException e){
            throw new RuntimeException(e);
        }
        return resultMap;
    }

    public Map<Integer, Set<String>> getInverseInstructorCriteriaAssignment(int assignId) {
        String sql = "SELECT user_id, criteria_id FROM instructor_criteria_assignment WHERE assign_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, assignId);
            ResultSet resultSet = statement.executeQuery();
            Map<Integer, Set<String>> criteriaIdToTas = new HashMap<>();
            while (resultSet.next()) {
                String userId = resultSet.getString("user_id");
                int criteriaId = resultSet.getInt("criteria_id");
                criteriaIdToTas.computeIfAbsent(criteriaId, k -> new HashSet<>()).add(userId);
            }
            return criteriaIdToTas;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Pair<Integer, String> getInstructorEvalAssignment(String uniqueId) {
        String tableName = "instructor_eval_assignment";
        String sql = "SELECT * FROM " + tableName + " WHERE unique_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId);
            ResultSet resultSet = statement.executeQuery();
            Pair<Integer, String> pair = null;
            while (resultSet.next()) {
                Integer criteriaId = resultSet.getInt("criteria_id");
                String groupId = resultSet.getString("group_id");
                pair = new Pair<>(criteriaId, groupId);
            }
            return pair;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public double getWeight(int criteriaId) {
        String tableName = "criteria";
        String sql = "SELECT * FROM " + tableName + " WHERE criteria_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, criteriaId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                double weight = resultSet.getDouble("weight");
                return weight;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0.0;
    }

    public List<List<Integer>> getAnswers(int assignId){
        List<List<Integer>> result = new ArrayList<>();
        String sql = "SELECT * FROM training WHERE assignment_id = ?";
        try(PreparedStatement statement = connection.prepareStatement(sql)){
            statement.setInt(1, assignId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()){
                //parsing through the answer string
                String strAnswer = resultSet.getString("answers");
                String[] answersArray = strAnswer.substring(1, strAnswer.length()-2).split("], ");
                for (String answer: answersArray){
                    String[] answerArray = answer.substring(1).split(",");
                    List<Integer> answerList = new ArrayList<>();
                    for(String elem: answerArray){
                        answerList.add(Integer.parseInt(elem));
                    }
                    result.add(answerList);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public Integer criteriaTaToPeerId(int taId) {
        String tableName = "criteria_correspondence";
        String sql = "SELECT * FROM " + tableName + " WHERE ta_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, taId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                int peerId = resultSet.getInt("peer_id");
                return peerId;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public Integer criteriaPeerToTaId(int peerId) {
        String tableName = "criteria_correspondence";
        String sql = "SELECT * FROM " + tableName + " WHERE peer_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, peerId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                int taId = resultSet.getInt("ta_id");
                return taId;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public Map<Integer, Set<InstructorEval>> dataLoadInstructorMC(int assignId) {
        Map<Integer, Set<InstructorEval>> resultMap = new HashMap<>();

        String tableName = "instructor_eval_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                // I think we can entirely ignore everything except for uniqueId and deductions
                String uniqueId = resultSet.getString("unique_id");
                String deductionStr = resultSet.getString("deductions");
                String[] deductions = deductionStr.substring(1, deductionStr.length()-1).split(",");
                Double deductionSum = 0.0;
                for (String ded : deductions) {
                    Double deduction = Double.parseDouble(ded.strip());
                    deductionSum += deduction;
                }

                // Look up the corresponding entry in the instructor_eval_assignment table in order
                // to get the group being graded + the criterion being graded
                Pair<Integer, String> pair = getInstructorEvalAssignment(uniqueId);
                int criteriaId = pair.getFirst();
                String groupId = pair.getSecond();

                // Look up the weight (point value) of this criterion

                double pointValue = this.getWeight(criteriaId);
                double score = Math.max(0, pointValue + deductionSum);

                // Create the InstructorEval and add to the resultMap
                if (!resultMap.containsKey(criteriaId)) {
                    resultMap.put(criteriaId, new HashSet<>());
                }
                resultMap.get(criteriaId).add(new InstructorEval(groupId, score));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return resultMap;
    }

    public Map<String, Map<Integer, List<String>>> dataLoadPeerFr(int assignId) throws ParseException {
        Map<String, Map<Integer, List<String>>> resultMap = new HashMap<>();

        String tableName = "peer_eval_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);


            ResultSet rs = statement.executeQuery();

            while (rs.next()) {
                String submissionId1 = rs.getString("submission_id1");
                String submissionId2 = rs.getString("submission_id2");
                String uniqueId = rs.getString("unique_id");
                String groupId1 = submissionId1.split("-")[1];
                String groupId2 = submissionId2.split("-")[1];

                Map<Integer, List<String>> groupData1 = resultMap.getOrDefault(groupId1, new HashMap<>());
                Map<Integer, List<String>> groupData2 = resultMap.getOrDefault(groupId2, new HashMap<>());

                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                String json = rs.getString(columnCount);
                JSONParser parser = new JSONParser();
                JSONObject jsonObj = (JSONObject) parser.parse(json);
                for (Integer criteriaId = 6; criteriaId <= 7; criteriaId++) {
                    JSONObject innerObj = (JSONObject) jsonObj.get(criteriaId.toString());
                    String value1 = (String) innerObj.get("1");
                    String value2 = (String) innerObj.get("2");
                    addToMap(groupData1, criteriaId, value1);
                    addToMap(groupData2, criteriaId, value2);
                }

                resultMap.put(groupId1, groupData1);
                resultMap.put(groupId2, groupData2);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return resultMap;
    }


    public Map<String, Map<Integer, List<String>>> dataLoadSelfFr(int assignId) {
        Map<String, Map<Integer, List<String>>> resultMap = new HashMap<>();

        String tableName = "self_eval_submission_" + assignId;
        String sql = "SELECT * FROM " + tableName;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery();

            while (rs.next()) {
                String submissionId = rs.getString("submission_id");
                String userId = submissionId.split("-")[1];

                Map<Integer, List<String>> userIdData = resultMap.getOrDefault(userId, new HashMap<>());
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    if (!columnName.startsWith("free_response")) {
                        continue;
                    }
                    int criteriaId = Integer.parseInt(columnName.substring(columnName.indexOf("_q") + 2));
                    String value1 = rs.getString(i);
                    addToMap(userIdData, criteriaId, value1);
                }

                resultMap.put(userId, userIdData);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return resultMap;
    }

    private static void addToMap(Map<Integer, List<String>> map, int criteriaId, String value) {
        if (!map.containsKey(criteriaId)) {
            map.put(criteriaId, new ArrayList<>());
        }
        map.get(criteriaId).add(value);
    }


    public void createAllTables() throws SQLException {
        createSchema(connection);

        // Check if the assignment table exists; if not, create it
        createAssignmentTable(connection);

        // Check if the student table exists; if not, create it
        createStudentTable(connection);

        // Check if the group correspondence table exists; if not, create it
        createGroupCorrespondenceTable(connection);

        // Check if the instructor table exists; if not, create it
        createInstructorTable(connection);

        // Check if the instructor criteria assignment table exists; if not, create it
        createInstructorCriteriaAssignmentTable(connection);

        // Check if the criteria table exists; if not, create it
        createCriteriaTable(connection);

        // Check if the criteria correspondence table exists; if not, create it
        createCriteriaCorrespondenceTable(connection);

        // Check if the rubric table exists; if not, create it
        createRubricTable(connection);

        // Check if the training table exists; if not, create it
        createTrainingTable(connection);

        // Check if the submission table exists; if not, create it
        createSubmissionTable(connection);

        // Check if the peer_eval_assignment table exists; if not, create it
        createPeerEvalAssignmentTable(connection);

        // Check if the self_eval_assignment table exists; if not, create it
        createSelfEvalAssignmentTable(connection);

        // Check if the instructor_eval_assignment table exists; if not, create it
        createInstructorEvalAssignmentTable(connection);

        // Check if the training_assignment table exists; if not, create it
        createTrainingAssignmentTable(connection);
        // Check if the assignment_draft table exists; if not, create it
        createAssignmentDraftTable(connection);

        // Check if the student_assignment_group table exists; if not, create it
        createStudentAssignmentGroupTable(connection);
    }

    private void createSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createSchemaQuery = "CREATE SCHEMA IF NOT EXISTS " + this.dbName + ";";
            stmt.executeUpdate(createSchemaQuery);
        }
    }

    private void createAssignmentTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createAssignmentTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`assignment` (" +
                "`assignment_id` INT NOT NULL," +
                "`assignment_name` text NOT NULL," +
                "`student_submission_ddl` DATETIME NOT NULL," +
                "`pairs_per_student` INT NOT NULL," +
                "`evals_per_ta_per_criteria` INT NOT NULL," +
                "PRIMARY KEY (`assignment_id`)," +
                "UNIQUE KEY `assignment_id_UNIQUE` (`assignment_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createAssignmentTableQuery);
        }
    }

    private void createStudentTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createUserTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`student` (" +
                "`user_id` varchar(100) NOT NULL," +
                "`user_name` text NOT NULL," +
                "`group_id` text DEFAULT NULL," +
                "`anon_id` text DEFAULT NULL," +
                "`extension` DATETIME DEFAULT NULL," +
                "PRIMARY KEY (`user_id`)," +
                "UNIQUE KEY `user_id_UNIQUE` (`user_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createUserTableQuery);
        }
    }

    private void createGroupCorrespondenceTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createUserTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`group_correspondence` (" +
                "`group_id` varchar(100) NOT NULL," +
                "`anon_id` text NOT NULL," +
                "PRIMARY KEY (`group_id`)," +
                "UNIQUE KEY `group_id_UNIQUE` (`group_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createUserTableQuery);
        }
    }

    private void createInstructorTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createInstructorTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`instructor` (" +
                "`user_id` varchar(100) NOT NULL," +
                "`role` varchar(20) NOT NULL," +
                "`user_name` text NOT NULL," +
                "PRIMARY KEY (`user_id`)," +
                "UNIQUE KEY `user_id_UNIQUE` (`user_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createInstructorTableQuery);
        }
    }

    private void createInstructorCriteriaAssignmentTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`instructor_criteria_assignment` (" +
                "`user_id` varchar(100) NOT NULL," +
                "`assign_id` INT NOT NULL," +
                "`criteria_id` INT NOT NULL," +
                "PRIMARY KEY (`user_id`, `assign_id`, `criteria_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(sql);
        }
    }

    private void createCriteriaTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createCriteriaTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`criteria` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`criteria_id` INT NOT NULL," +
                "`criteria_text` text NOT NULL," +
                "`free_response` TINYINT NOT NULL," +
                "`eval_type` TINYINT NOT NULL," +
                "`max_char` INT NOT NULL," +
                "`weight` DECIMAL(5,2) NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createCriteriaTableQuery);
        }
    }

    private void createCriteriaCorrespondenceTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // unique_id is assignment_id-ta_id
            String createCriteriaCorrespondenceTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`criteria_correspondence` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assign_id` INT NOT NULL," +
                "`ta_id` INT NOT NULL," +
                "`peer_id` INT NOT NULL," +
                "`self_id` INT NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createCriteriaCorrespondenceTableQuery);
        }
    }

    private void createRubricTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()){
            String createRubricTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`rubric` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`criteria_id` INT NOT NULL," +
                "`headers` text NOT NULL," +
                "`deduction_text` text NOT NULL," +
                "`deduction_pts` text NOT NULL," +
                "`mutually_exclusive` tinyint NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createRubricTableQuery);
        }
    }

    private void createTrainingTable(Connection conn) throws SQLException{
        try (Statement stmt = conn.createStatement()){
            String createTrainingTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`training` (" +
                "`assignment_id` INT NOT NULL," +
                "`submission1_paths` text," +
                "`submission2_paths` text," +
                "`answers` text NOT NULL," +
                "`feedback` text NOT NULL," +
                "PRIMARY KEY (`assignment_id`)," +
                "UNIQUE KEY `assignment_id_UNIQUE` (`assignment_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createTrainingTableQuery);
        }
    }

    private void createSubmissionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createSubmissionTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`submission` (" +
                "`submission_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`assignment_name` text NOT NULL," +
                "`group_id` text NOT NULL," +
                "`submission_path` text NOT NULL," +
                "PRIMARY KEY (`submission_id`)," +
                "UNIQUE KEY `submission_id_UNIQUE` (`submission_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createSubmissionTableQuery);
        }
    }


    private void createPeerEvalAssignmentTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createPeerEvalAssignmentTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`peer_eval_assignment` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`user_id` text NOT NULL," +
                "`group1_id` text NOT NULL," +
                "`group2_id` text NOT NULL," +
                "`status` TINYINT NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createPeerEvalAssignmentTableQuery);
        }
    }

    private void createSelfEvalAssignmentTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createSelfEvalAssignmentTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`self_eval_assignment` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`user_id` text NOT NULL," +
                "`status` TINYINT NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createSelfEvalAssignmentTableQuery);
        }
    }

    private void createInstructorEvalAssignmentTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createInstructorEvalAssignmentTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`instructor_eval_assignment` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`round` TINYINT NOT NULL," +
                "`idx` INT NOT NULL," +
                "`user_id` text NOT NULL," +
                "`criteria_id` INT NOT NULL," +
                "`group_id` text NOT NULL," +
                "`status` TINYINT NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createInstructorEvalAssignmentTableQuery);
        }
    }

    public void createEvalSubmissionTable(int assignmentId, Map<Integer, Integer> freeToMaxChar, int evalType) {
        String tableName = "";
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("CREATE TABLE ");

        // note: 0 is peer, 1 is self, and 2 is instructor :)
        if (evalType == 0) {
            tableName = "peer_eval_submission_" + assignmentId;
            queryBuilder.append(tableName).append(" (");
            queryBuilder.append("submission_id1 text NOT NULL, ");
            queryBuilder.append("submission_id2 text NOT NULL, ");
        } else if (evalType == 1){
            tableName = "self_eval_submission_" + assignmentId;
            queryBuilder.append(tableName).append(" (");
            queryBuilder.append("submission_id text NOT NULL, ");
        } else if (evalType == 2) {
            tableName = "instructor_eval_submission_" + assignmentId;
            queryBuilder.append(tableName).append(" (");
            queryBuilder.append("submission_id text NOT NULL, ");
            queryBuilder.append("deductions text NOT NULL, ");
        } else if (evalType == 3) {
            tableName = "training_submission_" + assignmentId;
            queryBuilder.append(tableName).append(" (");
            queryBuilder.append("submission_id text NOT NULL, ");
        }

        queryBuilder.append("unique_id varchar(100) NOT NULL, ");
        queryBuilder.append("submit_time datetime NOT NULL, ");
        queryBuilder.append("choices text NOT NULL, ");
        queryBuilder.append("free_responses mediumtext NOT NULL, ");

        queryBuilder.append("PRIMARY KEY (unique_id), ");
        queryBuilder.append("UNIQUE KEY unique_id_UNIQUE (unique_id)");
        queryBuilder.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");

        try {
            Statement statement = connection.createStatement();
            statement.executeUpdate(queryBuilder.toString());
            System.out.println("Table " + tableName + " created successfully.");

            statement.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void createTrainingAssignmentTable(Connection conn){

        try (Statement stmt = conn.createStatement()){
            String createTrainingAssignmentQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`training_assignment` (" +
                "`unique_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`user_id` text NOT NULL," +
                "`status` TINYINT NOT NULL," +
                "PRIMARY KEY (`unique_id`)," +
                "UNIQUE KEY `unique_id_UNIQUE` (`unique_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createTrainingAssignmentQuery);
        } catch(SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void createStudentAssignmentGroupTable(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`student_assignment_group` (" +
                "`user_id` varchar(100) NOT NULL," +
                "`assignment_id` INT NOT NULL," +
                "`group_id` varchar(100) NOT NULL," +
                "PRIMARY KEY (`user_id`, `assignment_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void createAssignmentDraftTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String createAssignmentTableQuery = "CREATE TABLE IF NOT EXISTS `" + this.dbName + "`.`assignment_draft` (" +
                "`draft_id` INT NOT NULL AUTO_INCREMENT," +
                "`json` mediumtext NOT NULL," +
                "PRIMARY KEY (`draft_id`)," +
                "UNIQUE KEY `draft_id_UNIQUE` (`draft_id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
            stmt.executeUpdate(createAssignmentTableQuery);
        }
    }
    
    public int getNextIndexGradingAssignment(int assignId, String taId) {
        String sql = "SELECT * FROM instructor_eval_assignment WHERE user_id = ? AND assignment_id = ?";
        int count = 0;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, taId);
            statement.setInt(2, assignId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                count++;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return count;
    }

    public int getOffsetRound2GradingAssignment(int assignId, String taId) {
        String sql = "SELECT * FROM instructor_eval_assignment WHERE user_id = ? AND assignment_id = ? AND round = 0";
        int count = 0;
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, taId);
            statement.setInt(2, assignId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                count++;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return count;
    }

    public String getCriteriaTextFromTable(int criteriaId, int assignId) throws SQLException {
        String criteriaText = null;

        String query = "SELECT criteria_text FROM criteria WHERE assignment_id = ? AND criteria_id = ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setInt(1, assignId);
            preparedStatement.setInt(2, criteriaId);

            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    criteriaText = resultSet.getString("criteria_text");
                }
            }
        }
        return criteriaText;
    }
    public List<String> getGroupIdsForAssignment(int assignmentId) {
        List<String> groupIds = new ArrayList<>();
        String sqlQuery = "SELECT group_id FROM submission WHERE assignment_id = ?";

        try {
            PreparedStatement preparedStatement = connection.prepareStatement(sqlQuery);
            preparedStatement.setInt(1, assignmentId);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String groupId = resultSet.getString("group_id");
                    groupIds.add(groupId);
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return groupIds;
    }

    public List<String> getStudentUserIds() throws SQLException {
        List<String> userIds = new ArrayList<>();
        String query = "SELECT * FROM student";
        PreparedStatement preparedStatement = connection.prepareStatement(query);
        ResultSet results = preparedStatement.executeQuery();
        while (results.next()) {
            String userId = results.getString("user_id");
            userIds.add(userId);
        }
        return userIds;
    }

    public Map<String, String> getStudentToGroupMap() throws SQLException {
        Map<String, String> studentToGroup = new HashMap<>();
        String query = "SELECT user_id, group_id FROM student";
        PreparedStatement preparedStatement = connection.prepareStatement(query);
        ResultSet results = preparedStatement.executeQuery();
        while (results.next()) {
            String userId = results.getString("user_id");
            String groupId = results.getString("group_id");
            if (groupId != null && !groupId.isEmpty()) {
                studentToGroup.put(userId, groupId);
            }
        }
        return studentToGroup;
    }


    public Map<String, Double> loadPeerEvalCompletion() throws SQLException {
        List<String> userIds = getStudentUserIds();
        Map<String, Double> completion = new HashMap<>();
        Map<String, double[]> grades = new HashMap<>();

        for (String userId : userIds) {
            completion.put(userId, 0.0);
            grades.put(userId, new double[]{0.0, 0.0, 0.0});
        }

        // Count peer evals
        String[] queries = {"SELECT * FROM training_assignment", "SELECT * FROM peer_eval_assignment", "SELECT * FROM self_eval_assignment"};
        double[] weights = {0.2, 0.6, 0.2};
        int[] numExpected = {2, 3, 2};
        for (int i = 0; i < queries.length; i++) {
        String query = queries[i];
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            ResultSet results = preparedStatement.executeQuery();
            while (results.next()) {
                String userId = results.getString("user_id");
                int isComplete = results.getInt("status");
                if (isComplete != 0) {
                    grades.get(userId)[i] += 1;
                }
            }

        // normalize
        for (String userId : userIds) {
            double[] oldGrades = grades.get(userId);
            oldGrades[i] /= numExpected[i];
                grades.put(userId, oldGrades);
            }
        }


        for (String userId : userIds) {
            completion.put(userId, grades.get(userId)[0] * weights[0] + grades.get(userId)[1] * weights[1] + grades.get(userId)[2] * weights[2]);
        }
        return completion;
    }

    //NOTE: TRY to generalize for instructor :)
    private List<Integer> getSelfEvalMCCriteriaIDs(int assignID) throws SQLException {
        List<Integer> criteriaIDs = new ArrayList<>();
        String query = "SELECT * from criteria WHERE free_response = 0 AND eval_type = 1 AND assignment_id = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(query);
        preparedStatement.setInt(1, assignID);
        ResultSet results = preparedStatement.executeQuery();
        while (results.next()) {
            int criteriaID = results.getInt("criteria_id");
            criteriaIDs.add(criteriaID);
        }
        Collections.sort(criteriaIDs);
        return criteriaIDs;
    }

    public List<Integer> getInstructorEvalMCCriteriaIDs(int assignID) throws SQLException {
        List<Integer> criteriaIDs = new ArrayList<>();
        String query = "SELECT * from criteria WHERE eval_type = 2 AND assignment_id = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(query);
        preparedStatement.setInt(1, assignID);
        ResultSet results = preparedStatement.executeQuery();
        while (results.next()) {
            int criteriaID = results.getInt("criteria_id");
            criteriaIDs.add(criteriaID);
        }
        Collections.sort(criteriaIDs);
        return criteriaIDs;
    }


    public void addExtension(String userId, String extendedDeadline) throws SQLException {
        String sql = "UPDATE student SET extension=? WHERE user_id=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setTimestamp(1, Timestamp.valueOf(extendedDeadline));
        statement.setString(2, userId);
        statement.executeUpdate();
    }

    /**
     * Returns true if any rows exist in group_correspondence (groups have been loaded).
     */
    public boolean groupsExist() {
        String sql = "SELECT 1 FROM group_correspondence LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Clears all group assignments: deletes group_correspondence rows and nulls student.group_id.
     */
    public void clearGroupData() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM group_correspondence");
            stmt.executeUpdate("UPDATE student SET group_id = NULL");
        } catch (SQLException e) {
            System.out.println("Exception in clearGroupData: " + e);
        }
    }
}
