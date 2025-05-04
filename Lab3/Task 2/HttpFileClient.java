import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class HttpFileClient {
    private static final String SERVER_URL = "http://localhost:8080";
    private static final String DOWNLOAD_URL = SERVER_URL + "/download";
    private static final String UPLOAD_URL = SERVER_URL + "/upload";
    private static final String LIST_URL = SERVER_URL + "/list";
    
    private static final String DOWNLOADS_DIR = "./client";
    
    public static void main(String[] args) {
        createDirectory(DOWNLOADS_DIR);
        
        Scanner scanner = new Scanner(System.in);
        
        boolean running = true;
        while (running) {
            System.out.println("\n=== File Transfer Client ===");
            System.out.println("1. Upload file");
            System.out.println("2. Download file");
            System.out.println("3. List available files");
            System.out.println("4. Exit");
            System.out.print("Enter your choice: ");
            
            String choice = scanner.nextLine();
            
            switch (choice) {
                case "1":
                    uploadFile(scanner);
                    break;
                case "2":
                    downloadFile(scanner);
                    break;
                case "3":
                    listFiles();
                    break;
                case "4":
                    running = false;
                    System.out.println("Exiting...");
                    break;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
        
        scanner.close();
    }
    
    private static void uploadFile(Scanner scanner) {
        try {
            System.out.print("Enter the path of the file to upload: ");
            String filePath = scanner.nextLine();
            
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                System.out.println("Error: File does not exist or is not a valid file.");
                return;
            }
            
            String filename = file.getName();
            System.out.println("Uploading file: " + filename);
            
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            
            URL url = new URL(UPLOAD_URL + "?filename=" + encodedFilename);
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            
            try (FileInputStream fileInputStream = new FileInputStream(file);
                 OutputStream outputStream = connection.getOutputStream()) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long fileSize = file.length();
                
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    
                    int progress = (int) ((totalBytes * 100) / fileSize);
                    System.out.print("\rUploading: " + progress + "% complete");
                }
                System.out.println("\nUpload completed!");
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    System.out.println("Server response: " + response.toString());
                }
            } else {
                System.out.println("Error: Server returned code " + responseCode);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream()))) {
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    System.out.println("Error message: " + response.toString());
                }
            }
            
            connection.disconnect();
            
        } catch (Exception e) {
            System.out.println("Error uploading file: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void downloadFile(Scanner scanner) {
        try {
            listFiles();
            
            System.out.print("Enter the name of the file to download: ");
            String filename = scanner.nextLine();
            
            if (filename.isEmpty()) {
                System.out.println("Filename cannot be empty.");
                return;
            }
            
            System.out.println("Downloading file: " + filename);
            
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            
            URL url = new URL(DOWNLOAD_URL + "?filename=" + encodedFilename);
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == 200) {
                int contentLength = connection.getContentLength();
                
                File outputFile = new File(DOWNLOADS_DIR, filename);
                
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                        
                        if (contentLength > 0) {
                            int progress = (int) ((totalBytes * 100) / contentLength);
                            System.out.print("\rDownloading: " + progress + "% complete");
                        } else {
                            System.out.print("\rDownloading: " + totalBytes + " bytes");
                        }
                    }
                    
                    System.out.println("\nDownload completed! File saved to: " + outputFile.getAbsolutePath());
                }
            } else if (responseCode == 404) {
                System.out.println("Error: File not found on server.");
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream()))) {
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line).append("\n");
                    }
                    System.out.println(response.toString());
                }
            } else {
                System.out.println("Error: Server returned code " + responseCode);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream()))) {
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line).append("\n");
                    }
                    System.out.println(response.toString());
                }
            }
            
            connection.disconnect();
            
        } catch (Exception e) {
            System.out.println("Error downloading file: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void listFiles() {
        try {
            System.out.println("Fetching list of available files...");
            
            URL url = new URL(LIST_URL);
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }
            } else {
                System.out.println("Error: Server returned code " + responseCode);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream()))) {
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line).append("\n");
                    }
                    System.out.println(response.toString());
                }
            }
            
            connection.disconnect();
            
        } catch (Exception e) {
            System.out.println("Error listing files: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void createDirectory(String dirPath) {
        File directory = new File(dirPath);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (created) {
                System.out.println("Created directory: " + dirPath);
            } else {
                System.err.println("Failed to create directory: " + dirPath);
            }
        }
    }
}