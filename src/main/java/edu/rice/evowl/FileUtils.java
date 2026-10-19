package edu.rice.evowl;


import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility functions for file I/O and directory operations used across the tool's commands.
 */
public class FileUtils {

    /**
     * Recursively deletes a directory and all of its contents.
     *
     * @return true if the directory (and all its contents) were deleted successfully
     */
    public static boolean deleteDirectory(File path) {
        if (path.exists()) {
            File[] files = path.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        deleteDirectory(f);
                    } else {
                        f.delete();
                    }
                }
            }
        }
        return path.delete();
    }

    /**
     * This method should take as its input a path to a directory. If the input path
     * does not point to a directory, this method should return a Set<String> containing
     * only the input path. Otherwise, it should return a Set<String> containing the
     * relative paths to all normal files (not directories) that are contained within
     * the specified directory, either directly or indirectly (within a subdirectory).
     * For example, given the input “/dir1/dir2” and the following directory structure:
     *
     * dir1/
     * |---dir2/
     *      |---file1.txt
     *      |---file2.txt
     *      |---file3.txt
     *      |---dir3/
     *           |---file4.txt
     *      |---dir4/
     *           |---dir5/
     *                |---file5.txt
     *
     * ...this method should return the set containing {“file1.txt”, “file2.txt”,
     * “file3.txt”, “dir3/file4.txt”, “dir4/dir5/file5.txt”}.
     *
     * @param dirpath the path
     * @return the set of relative paths to all normal files contained within dirpath
     */
    public static Set<String> getFilenames(String dirpath, String postfix) {
        if (!new File(dirpath).isDirectory()) {
            throw new IllegalArgumentException("Directory not found: " + dirpath);
        }
        Set<String> fileNames = getFilenamesHelper(dirpath);
        Set<String> retval = new HashSet<>();
        for (String fileName: fileNames) {
            if (fileName.endsWith(postfix) && !fileName.substring(dirpath.length() + 1).startsWith("coverage-after")) {
                retval.add(fileName.substring(dirpath.length() + 1));
            }
        }
        return retval;
    }

    /** 
     * Recursive helper that returns the absolute paths of all normal files under dirpath.
     * 
     * @param dirpath the path to the directory to search
     * @return the set of absolute paths of all normal files under dirpath
     */
    public static Set<String> getFilenamesHelper(String dirpath) {
        File f = new File(dirpath);
        if (!f.isDirectory()) {
            Set<String> s = new HashSet<>();
            s.add(dirpath);
            return s;
        }

        Set<String> retval = new HashSet<>();
        String[] filePaths = f.list();
        for (String filePath : filePaths) {
            Set<String> results = getFilenamesHelper(dirpath + "/" + filePath);
            retval.addAll(results);
        }
        return retval;
    }

    /** 
     * Returns a list of [relativePath, fileContent] pairs for each path in relativeSubmission.
     * 
     * @param relativeSubmission the set of relative paths to the files to read
     * @param subPath the path to the directory to read the files from
     * @return the list of [relativePath, fileContent] pairs for each path in relativeSubmission
     */
    public static List<List<String>> getSubJson(Set<String> relativeSubmission, String subPath) {
        List<List<String>> result = new ArrayList<>();
        for (String path: relativeSubmission) {
            List<String> subList = new ArrayList<>();
            subList.add(path);
            String absolutePath =  subPath + "/" + path;
            String fileContent = getContentByPath(absolutePath);
            subList.add(fileContent);
            result.add(subList);

        }
        return result;
    }

    /** 
     * Reads the file at absolutePath as UTF-8 and returns its contents,
     * or "File not found!" if the path is not a regular file.
     * 
     * @param absolutePath the path to the file to read
     * @return the contents of the file at absolutePath
     */
    private static String getContentByPath(String absolutePath) {
        StringBuilder builder = new StringBuilder();
        try {
            File file = new File(absolutePath);
            if(file.isFile() && file.exists()) {
                InputStreamReader isr = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String lineTxt = null;
                while ((lineTxt = br.readLine()) != null) {
                    builder.append(lineTxt).append(System.lineSeparator());
                }
                br.close();
            } else {
                builder.append("File not found!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return builder.toString();
    }

}
