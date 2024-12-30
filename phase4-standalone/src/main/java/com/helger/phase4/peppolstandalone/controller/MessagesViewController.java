package com.helger.phase4.peppolstandalone.controller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.helger.commons.io.file.FilenameHelper;

@Controller
public class MessagesViewController {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MessagesViewController.class);

    @Value("${phase4.datapath:target/phase4-data}")
    private String dataPath;

    @GetMapping("/messages")
    public String viewMessages(Model model) {
        final List<MessageInfo> messages = new ArrayList<>();
        
        // Use the configured data path
        if (dataPath != null) {
            final File dataDir = new File(dataPath);
            if (dataDir.exists() && dataDir.isDirectory()) {
                // List all files in directory
                final File[] files = dataDir.listFiles();
                if (files != null) {
                    for (final File file : files) {
                        if (file.isFile()) {
                            final String filename = file.getName();
                            final String extension = FilenameHelper.getExtension(filename);
                            
                            // Only show .soap files as they represent messages
                            if (".soap".equalsIgnoreCase(extension)) {
                                try {
                                    messages.add(new MessageInfo(
                                        filename,
                                        Files.getLastModifiedTime(file.toPath()).toMillis(),
                                        file.length()
                                    ));
                                } catch (Exception e) {
                                    LOGGER.error("Error reading file: " + filename, e);
                                }
                            }
                        }
                    }
                }
            } else {
                LOGGER.warn("Data directory does not exist or is not a directory: {}", dataPath);
            }
        }
        
        // Sort by date descending
        Collections.sort(messages);
        
        model.addAttribute("messages", messages);
        return "messages";
    }

    public static class MessageInfo implements Comparable<MessageInfo> {
        private final String filename;
        private final long timestamp;
        private final long size;

        public MessageInfo(String filename, long timestamp, long size) {
            this.filename = filename;
            this.timestamp = timestamp;
            this.size = size;
        }

        public String getFilename() { return filename; }
        public long getTimestamp() { return timestamp; }
        public long getSize() { return size; }

        @Override
        public int compareTo(MessageInfo o) {
            return Long.compare(o.timestamp, this.timestamp); // Descending
        }
    }
} 