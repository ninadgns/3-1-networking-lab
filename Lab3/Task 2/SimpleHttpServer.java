import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Vector;
import java.util.concurrent.Executors;

public class SimpleHttpServer {
    private static final String FILES_DIRECTORY = "./files";
    public static Vector<String> fileNames = new Vector<>();
    private static final int PORT = 8080;

    static void populateFileNames() {
        File directory = new File(FILES_DIRECTORY);
        fileNames = new Vector<>();
        
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                System.err.println("Failed to create directory: " + FILES_DIRECTORY);
                return;
            }
            System.out.println("Created directory: " + FILES_DIRECTORY);
        }
        
        Path directoryPath = Paths.get(FILES_DIRECTORY);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    fileNames.add(file.getFileName().toString());
                }
            }
            System.out.println("Available files: " + fileNames);
        } catch (IOException e) {
            System.err.println("Error reading directory: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        populateFileNames();
        
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        server.createContext("/download", new DownloadHandler());
        server.createContext("/upload", new UploadHandler());
        server.createContext("/list", new ListFilesHandler());
        
        server.setExecutor(Executors.newFixedThreadPool(10));
        
        server.start();
        
        System.out.println("Server is running on port " + PORT);
        System.out.println("File directory: " + FILES_DIRECTORY);
    }

    static class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("GET")) {
                    sendErrorResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                String query = exchange.getRequestURI().getQuery();
                if (query == null || !query.startsWith("filename=")) {
                    sendErrorResponse(exchange, 400, "Bad Request: Missing filename parameter");
                    return;
                }
                
                String filename = URLDecoder.decode(query.substring(9), StandardCharsets.UTF_8);
                System.out.println("Download request for file: " + filename);
                
                if (!fileNames.contains(filename)) {
                    sendErrorResponse(exchange, 404, "File Not Found: " + filename);
                    return;
                }
                
                Path filePath = Paths.get(FILES_DIRECTORY, filename);
                byte[] fileData = Files.readAllBytes(filePath);
                
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().add("Content-Disposition", 
                        "attachment; filename=\"" + filename + "\"");
                
                exchange.sendResponseHeaders(200, fileData.length);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(fileData);
                }
                
                System.out.println("File sent successfully: " + filename);
                
            } catch (Exception e) {
                System.err.println("Error in download handler: " + e.getMessage());
                try {
                    sendErrorResponse(exchange, 500, "Server Error: " + e.getMessage());
                } catch (IOException ex) {
                    System.err.println("Failed to send error response: " + ex.getMessage());
                }
            }
        }
    }

    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    sendErrorResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                String query = exchange.getRequestURI().getQuery();
                if (query == null || !query.startsWith("filename=")) {
                    sendErrorResponse(exchange, 400, "Bad Request: Missing filename parameter");
                    return;
                }
                
                String filename = URLDecoder.decode(query.substring(9), StandardCharsets.UTF_8);
                System.out.println("Upload request for file: " + filename);
                
                File outputFile = new File(FILES_DIRECTORY, filename);
                
                try (InputStream is = exchange.getRequestBody();
                     OutputStream os = new FileOutputStream(outputFile)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    System.out.println("File uploaded successfully: " + filename + 
                                      " (" + totalBytes + " bytes)");
                }
                
                populateFileNames();
                
                String response = "File uploaded successfully: " + filename;
                sendTextResponse(exchange, 200, response);
                
            } catch (Exception e) {
                System.err.println("Error in upload handler: " + e.getMessage());
                try {
                    sendErrorResponse(exchange, 500, "Server Error: " + e.getMessage());
                } catch (IOException ex) {
                    System.err.println("Failed to send error response: " + ex.getMessage());
                }
            }
        }
    }

    static class ListFilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("GET")) {
                    sendErrorResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                populateFileNames();
                
                StringBuilder response = new StringBuilder();
                response.append("Available files:\n");
                
                if (fileNames.isEmpty()) {
                    response.append("No files available.");
                } else {
                    for (String file : fileNames) {
                        response.append("- ").append(file).append("\n");
                    }
                }
                
                sendTextResponse(exchange, 200, response.toString());
                
            } catch (Exception e) {
                System.err.println("Error in list files handler: " + e.getMessage());
                try {
                    sendErrorResponse(exchange, 500, "Server Error: " + e.getMessage());
                } catch (IOException ex) {
                    System.err.println("Failed to send error response: " + ex.getMessage());
                }
            }
        }
    }

    private static void sendErrorResponse(HttpExchange exchange, int code, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static void sendTextResponse(HttpExchange exchange, int code, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}