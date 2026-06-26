package org.processCV;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.Connection;

import org.baraza.DB.BDB;
import org.baraza.DB.BQuery;
import org.json.JSONObject;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

@MultipartConfig(fileSizeThreshold = 1024 * 1024 * 2, // 2 MB
                 maxFileSize = 1024 * 1024 * 10,      // 10 MB
                 maxRequestSize = 1024 * 1024 * 50)   // 50 MB
@WebServlet("/processCV")
public class uploadProcess extends HttpServlet {
    BDB db = null;
    String orgId = "0";
    String userID = "0";

    // Thread-local log capture: each request thread gets its own buffer.
    private static final ThreadLocal<StringBuilder> REQUEST_LOG = new ThreadLocal<>();

    private static class ThreadLocalPrintStream extends PrintStream {
        public ThreadLocalPrintStream(PrintStream base) { super(base, true); }

        @Override
        public void println(String s) {
            StringBuilder buf = REQUEST_LOG.get();
            if (buf != null) buf.append(s == null ? "null" : s).append('\n');
            else super.println(s);
        }

        @Override
        public void print(String s) {
            StringBuilder buf = REQUEST_LOG.get();
            if (buf != null) buf.append(s == null ? "null" : s);
            else super.print(s);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            StringBuilder buf = REQUEST_LOG.get();
            if (buf != null) buf.append(new String(b, off, len, StandardCharsets.UTF_8));
            else super.write(b, off, len);
        }
    }

    private String getLoggedInUserId(HttpServletRequest request) {
        try {
            String username = request.getUserPrincipal().getName();
            if (username == null || !username.matches("[a-zA-Z0-9@._\\-]+")) {
                System.out.println("Rejected unsafe username format");
                return "-1";
            }

            System.out.println("Logged in user from uploadProcess: " + username);
            String sql = "SELECT entity_id FROM entitys WHERE user_name = '" + username + "'";
            BQuery rs = new BQuery(db, sql);

            if (rs.moveNext()) {
                return rs.getString("entity_id");
            } else {
                System.out.println("No entity found for username: " + username);
                return "-1";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "-1";
        }
    }

    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        db = new BDB("java:/comp/env/jdbc/database");
        System.setOut(new ThreadLocalPrintStream(System.out));
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
                try {
                    userID = getLoggedInUserId(request);
                } catch (Exception e) {
                    userID = "-1";
                }
        doGet(request, response);
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        if (db != null && !db.isValid()) db.reconnect("java:/comp/env/jdbc/database");

        userID = getLoggedInUserId(request);
        System.out.println("Resolved userID in uploadProcess = " + userID);

        if (orgId == null) orgId = "0";
        if (userID == null) userID = "-1";

        StringBuilder logBuffer = new StringBuilder();
        REQUEST_LOG.set(logBuffer);

        try {
            
            Part filePart = request.getPart("cvFile");
            if (filePart == null || filePart.getSize() == 0) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("error", "No file uploaded");
                errorResponse.put("logs", logBuffer.toString());
                out.print(errorResponse.toString());
                return;
            }

            // Save uploaded file to temp location
            String originalFileName = Paths.get(filePart.getSubmittedFileName()).getFileName().toString();
            Path tempDir = Files.createTempDirectory("cv_upload_");
            Path tempFile = tempDir.resolve(originalFileName);
            try (InputStream input = filePart.getInputStream();
                 FileOutputStream output = new FileOutputStream(tempFile.toFile())) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }

            System.out.println("Saved to: " + tempFile);

            // Process CV
            readCV reader = new readCV();
            String rawText = reader.read(tempFile.toString());

            breakdownCV parser = new breakdownCV();
            JSONObject result = parser.extractCVData(rawText);
            System.out.println("Saving CV data to database for userID: " + userID);
            saveCVDataToDatabase(result, userID);

            // Clean up temp file
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);

            // Send JSON response with logs
            JSONObject responseObj = new JSONObject();
            responseObj.put("data", result);
            responseObj.put("status", "success");
            responseObj.put("message", "CV processed and saved to your profile");
            responseObj.put("logs", logBuffer.toString());
            out.print(responseObj.toString(2));

        } catch (Exception ex) {
            ex.printStackTrace();
            updateCVUploadStatus(userID, "failed");
            
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("error", "Processing failed: " + ex.getMessage());
            errorResponse.put("status", "failed");
            errorResponse.put("logs", logBuffer.toString());
            out.print(errorResponse.toString());
        } finally {
            REQUEST_LOG.remove();
            out.close();
        }
    }

    private void saveCVDataToDatabase(JSONObject cvData, String userID) {
        try {
            if (userID == null || userID.equals("-1")) {
                System.out.println("Invalid userID, cannot save CV data");
                return;
            }

            Connection conn = db.getDB();
            
            // 1. Update applicants table with extracted personal info
            String personalInfo = cvData.optJSONObject("personal_info").toString();
            JSONObject personObj = cvData.optJSONObject("personal_info");
            
            String updateAppSql = "UPDATE applicants SET cv_data = ?, cv_upload_status = ?, " +
                    "date_of_birth = ?, applicant_phone = ? WHERE entity_id = ?";
            
            try (PreparedStatement stmt = conn.prepareStatement(updateAppSql)) {
                stmt.setString(1, cvData.toString(2));  // Store full JSON in cv_data column
                stmt.setString(2, "completed");          
                stmt.setString(3, personObj.optString("dob", ""));
                stmt.setString(4, personObj.optString("phone", ""));
                stmt.setString(5, userID);
                
                int rowsUpdated = stmt.executeUpdate();
                System.out.println("Updated applicants table: " + rowsUpdated + " row(s)");
            }
            
            // 2. Insert education records
            JSONObject educationArray = cvData.optJSONArray("education");
            if (educationArray != null) {
                for (int i = 0; i < educationArray.length(); i++) {
                    JSONObject edu = educationArray.getJSONObject(i);
                    
                    String eduSql = "INSERT INTO education (org_id, entity_id, education_class_id, name_of_school, " +
                            "date_from, date_to, examination_taken, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    
                    try (PreparedStatement stmt = conn.prepareStatement(eduSql)) {
                        stmt.setString(1, "0");
                        stmt.setString(2, userID);
                        stmt.setString(3, edu.optString("edu-level", "N/A"));
                        stmt.setString(4, edu.optString("institution", ""));
                        stmt.setString(5, edu.optString("edu-from", ""));
                        stmt.setString(6, edu.optString("edu-to", ""));
                        stmt.setString(7, edu.optString("certification", ""));
                        stmt.setString(8, "Auto-extracted from CV");
                        
                        stmt.executeUpdate();
                        System.out.println("Inserted education record");
                    }
                }
            }
            
            // 3. Insert employment records
            JSONObject experienceArray = cvData.optJSONArray("experience");
            if (experienceArray != null) {
                for (int i = 0; i < experienceArray.length(); i++) {
                    JSONObject exp = experienceArray.getJSONObject(i);
                    
                    String empSql = "INSERT INTO employment (org_id, entity_id, employers_name, position_held, " +
                            "date_from, date_to, details) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    
                    try (PreparedStatement stmt = conn.prepareStatement(empSql)) {
                        stmt.setString(1, "0");
                        stmt.setString(2, userID);
                        stmt.setString(3, exp.optString("description", "").split(" - ")[0]);  
                        stmt.setString(4, exp.optString("role", ""));
                        stmt.setString(5, exp.optString("dates", "").split("-")[0]);  // Start date
                        stmt.setString(6, exp.optString("dates", "").split("-").length > 1 ? 
                                exp.optString("dates", "").split("-")[1] : "");  // End date
                        stmt.setString(7, exp.optString("description", ""));
                        
                        stmt.executeUpdate();
                        System.out.println("Inserted employment record");
                    }
                }
            }
            
            // 4. Insert skills records
            JSONObject skillsArray = cvData.optJSONArray("skills");
            if (skillsArray != null) {
                for (int i = 0; i < skillsArray.length(); i++) {
                    Object skill = skillsArray.get(i);
                    
                    String skillSql = "INSERT INTO skills (org_id, entity_id, skill_type_id, skill_level_id, details) " +
                            "VALUES (?, ?, ?, ?, ?)";
                    
                    try (PreparedStatement stmt = conn.prepareStatement(skillSql)) {
                        stmt.setString(1, "0");
                        stmt.setString(2, userID);
                        
                        if (skill instanceof JSONObject) {
                            JSONObject skillObj = (JSONObject) skill;
                            stmt.setString(3, skillObj.optString("skill", ""));
                            stmt.setString(4, "Intermediate");  // Default 
                            stmt.setString(5, skillObj.optString("category", "Technical"));
                        } else {
                            stmt.setString(3, skill.toString());
                            stmt.setString(4, "Intermediate");
                            stmt.setString(5, "Technical");
                        }
                        
                        stmt.executeUpdate();
                        System.out.println("Inserted skill record");
                    }
                }
            }
            
            // 5. Insert references records
            JSONObject referencesArray = cvData.optJSONArray("references");
            if (referencesArray != null) {
                for (int i = 0; i < referencesArray.length(); i++) {
                    JSONObject ref = referencesArray.getJSONObject(i);
                    
                    String refSql = "INSERT INTO address (org_id, table_id, table_name, address_name, company_name, " +
                            "email, phone_number) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    
                    try (PreparedStatement stmt = conn.prepareStatement(refSql)) {
                        stmt.setString(1, "0");
                        stmt.setString(2, userID);
                        stmt.setString(3, "referees");
                        stmt.setString(4, ref.optString("name", ""));
                        stmt.setString(5, ref.optString("organization", ""));
                        stmt.setString(6, ref.optString("email", ""));
                        stmt.setString(7, ref.optString("phone", ""));
                        
                        stmt.executeUpdate();
                        System.out.println("Inserted reference record");
                    }
                }
            }
            
            System.out.println("Successfully saved all CV data to database for userID: " + userID);
            
        } catch (Exception e) {
            System.err.println("Error saving CV data to database: " + e.getMessage());
            e.printStackTrace();
            updateCVUploadStatus(userID, "failed");
        }
    }

    private void updateCVUploadStatus(String userID, String status) {
        try {
            if (userID == null || userID.equals("-1")) return;
            
            Connection conn = db.getDB();
            String updateSql = "UPDATE applicants SET cv_upload_status = ? WHERE entity_id = ?";
            
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, status);
                stmt.setString(2, userID);
                stmt.executeUpdate();
                System.out.println("Updated CV status to: " + status);
            }
        } catch (Exception e) {
            System.err.println("Error updating CV status: " + e.getMessage());
        }
    }
}