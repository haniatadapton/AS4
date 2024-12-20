package com.helger.phase4.peppol.server.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.OutputKeys;
import java.io.StringReader;
import java.io.StringWriter;

@WebServlet("/messages")
public class MessagesViewerServlet extends HttpServlet {
    private static final TransformerFactory TRANSFORMER_FACTORY;
    private static final ObjectMapper OBJECT_MAPPER;
    
    static {
        TRANSFORMER_FACTORY = TransformerFactory.newInstance();
        TRANSFORMER_FACTORY.setAttribute("indent-number", 2);
        OBJECT_MAPPER = new ObjectMapper();
    }

    private String formatContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "Empty or invalid file content";
        }
        
        try {
            String trimmedContent = content.trim();
            if (trimmedContent.length() <= 1) {
                return "File contains insufficient content (length: " + trimmedContent.length() + ")";
            }
            
            if (trimmedContent.startsWith("{")) {
                Object json = OBJECT_MAPPER.readValue(trimmedContent, Object.class);
                return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            }
            else if (trimmedContent.startsWith("<")) {
                // Validate XML content length
                if (trimmedContent.length() < 5) { // Minimum valid XML length
                    return "Invalid XML content (too short)";
                }
                
                Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                
                StreamSource source = new StreamSource(new StringReader(trimmedContent));
                StringWriter writer = new StringWriter();
                StreamResult result = new StreamResult(writer);
                
                transformer.transform(source, result);
                return writer.toString();
            }
            return "Unrecognized content format: " + (trimmedContent.length() > 20 ? 
                   trimmedContent.substring(0, 20) + "..." : trimmedContent);
        } catch (Exception e) {
            return "Error processing content: " + e.getMessage() + 
                   "\nContent preview: " + (content.length() > 50 ? 
                   content.substring(0, 50) + "..." : content);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html>\n")
            .append("<head>\n")
            .append("    <title>AS4 Messages</title>\n")
            .append("    <meta charset=\"UTF-8\">\n")
            .append("    <style>\n")
            .append("        body { font-family: Arial, sans-serif; margin: 20px; }\n")
            .append("        table { width: 100%; border-collapse: collapse; margin-top: 20px; }\n")
            .append("        th, td { padding: 8px; text-align: left; border: 1px solid #ddd; }\n")
            .append("        th { background-color: #f2f2f2; }\n")
            .append("        .message-row:nth-child(even) { background-color: #f9f9f9; }\n")
            .append("        .message-content { \n")
            .append("            white-space: pre; \n")
            .append("            font-family: 'Courier New', Courier, monospace; \n")
            .append("            padding: 15px; \n")
            .append("            background-color: #f8f9fa; \n")
            .append("            border: 1px solid #ddd; \n")
            .append("            margin: 10px 0; \n")
            .append("            overflow-x: auto; \n")
            .append("            font-size: 14px; \n")
            .append("            line-height: 1.4; \n")
            .append("            display: none; \n")
            .append("        }\n")
            .append("        .view-button {\n")
            .append("            background-color: #4CAF50;\n")
            .append("            color: white;\n")
            .append("            padding: 6px 12px;\n")
            .append("            border: none;\n")
            .append("            border-radius: 4px;\n")
            .append("            cursor: pointer;\n")
            .append("        }\n")
            .append("        .view-button:hover { background-color: #45a049; }\n")
            .append("    </style>\n")
            .append("    <script>\n")
            .append("        function toggleContent(id) {\n")
            .append("            const content = document.getElementById('content-' + id);\n")
            .append("            const button = document.getElementById('btn-' + id);\n")
            .append("            if (content.style.display === 'none') {\n")
            .append("                content.style.display = 'block';\n")
            .append("                button.textContent = 'Hide Content';\n")
            .append("            } else {\n")
            .append("                content.style.display = 'none';\n")
            .append("                button.textContent = 'View Content';\n")
            .append("            }\n")
            .append("        }\n")
            .append("    </script>\n")
            .append("</head>\n")
            .append("<body>\n")
            .append("    <h1>AS4 Message History</h1>\n")
            .append("    <table>\n")
            .append("        <thead>\n")
            .append("            <tr>\n")
            .append("                <th>Filename</th>\n")
            .append("                <th>Type</th>\n")
            .append("                <th>Size</th>\n")
            .append("                <th>Date</th>\n")
            .append("                <th>Content</th>\n")
            .append("            </tr>\n")
            .append("        </thead>\n")
            .append("        <tbody>\n");

        // Update the path to the correct location
        Path dumpPath = Paths.get("C:", "O", "OneDrive - adapton AG", "Desktop", 
            "Success as4 peppol", "receiver (server)", "phase4-master", 
            "phase4-peppol-server-webapp", "generated", "as4dump");
        
        System.out.println("Searching for messages in: " + dumpPath.toAbsolutePath());

        AtomicBoolean filesFound = new AtomicBoolean(false);

        if (Files.exists(dumpPath)) {
            // Walk through all subdirectories
            List<AbstractMap.SimpleEntry<Path, FileTime>> allFiles = new ArrayList<>();
            
            Files.walk(dumpPath)
                 .filter(Files::isRegularFile)
                 .filter(path -> !path.toString().endsWith(".as4in"))
                 .forEach(path -> {
                     try {
                         allFiles.add(new AbstractMap.SimpleEntry<>(
                             path, 
                             Files.getLastModifiedTime(path)
                         ));
                     } catch (IOException e) {
                         System.err.println("Error reading file: " + path + " - " + e.getMessage());
                     }
                 });

            // Sort all files by last modified time
            allFiles.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            
            // Process the sorted files
            int messageCounter = 0;
            for (AbstractMap.SimpleEntry<Path, FileTime> entry : allFiles) {
                Path path = entry.getKey();
                filesFound.set(true);
                try {
                    String fileName = path.getFileName().toString();
                    String type = fileName.contains(".as4in") ? "Incoming" : 
                                fileName.contains(".as4out") ? "Outgoing" : "Other";
                    long size = Files.size(path);
                    LocalDateTime modified = LocalDateTime.ofInstant(
                        entry.getValue().toInstant(), 
                        TimeZone.getDefault().toZoneId()
                    );
                    
                    // Read file content
                    String content = Files.readString(path);
                    if (content == null || content.trim().isEmpty()) {
                        System.err.println("Warning: Empty file found: " + path);
                        content = "Empty file";
                    }
                    content = formatContent(content);
                    content = content.replace("<", "&lt;")
                                   .replace(">", "&gt;");
                    
                    html.append("<tr class=\"message-row\">")
                        .append("<td>").append(fileName).append("</td>")
                        .append("<td>").append(type).append("</td>")
                        .append("<td>").append(size/1024).append(" KB</td>")
                        .append("<td>").append(modified.format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                        .append("</td>")
                        .append("<td>")
                        .append("<button class='view-button' id='btn-").append(messageCounter)
                        .append("' onclick='toggleContent(").append(messageCounter).append(")'>View Content</button>")
                        .append("<div class='message-content' id='content-").append(messageCounter)
                        .append("'>").append(content).append("</div>")
                        .append("</td>")
                        .append("</tr>\n");
                    
                    messageCounter++;
                } catch (IOException e) {
                    System.err.println("Error reading file: " + path + " - " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        if (!filesFound.get()) {
            html.append("<tr><td colspan='4' style='text-align: center;'>No messages found</td></tr>");
        }

        html.append("        </tbody>\n")
            .append("    </table>\n")
            .append("</body>\n")
            .append("</html>");

        response.getWriter().write(html.toString());
    }
} 