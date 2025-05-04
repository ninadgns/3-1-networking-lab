
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Vector;

public class SimpleHttpServer {
    public static Vector<String> fileNames = new Vector<>();

    static void populateFileNames() throws IOException {
        String directoryPath = "./files";
        Path directory = Paths.get(directoryPath);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                fileNames.add(file.getFileName().toString());
            }
        }

    }

    public static void main(String[] args) throws IOException {
        populateFileNames();
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/download", new DownloadHandler());
        server.createContext("/upload", new UploadHandler());

        server.start();

        System.out.println("Server is running on port 8000");
    }

    static class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, 0);
            }
            var query = exchange.getRequestURI().getQuery().split("=")[1];
            System.out.println(query);
            if (!fileNames.contains(query)) {
                exchange.sendResponseHeaders(404, 0);
            }
            Path path = Paths.get("./files/" + query);
            byte[] arr = Files.readAllBytes(path);

            var ps = exchange.getResponseHeaders();
            var os = exchange.getResponseBody();
            ps.add("Content-Type", "application/octet-stream");
            ps.add("Content-Disposition", "attachment; filename*=UTF-8''" + query);
            exchange.sendResponseHeaders(200, arr.length);
            os.write(arr);
        }
    }

    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
            }
            FileOutputStream os = new FileOutputStream("upload.txt");
            var dataIn = exchange.getRequestBody();
            byte[] fileBytes = dataIn.readAllBytes();
            os.write(Arrays.copyOfRange(fileBytes, 0, fileBytes.length));
        }
    }
}