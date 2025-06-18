/**
 * Constants used throughout the TCP implementation
 */
public class Constants {

    public static final int SERVER_PORT = 8080;
    public static final int WINDOW_SIZE = 4096;
    public static final String OUTPUT_FILE_PREFIX = "received_file_";
    public static final int BUFFER_SIZE = 8192;
    public static final int MAX_SEGMENT_SIZE = 730;

    public static final String SERVER_HOST = "192.168.107.167";
    public static final int CLIENT_PORT = 12345;
    public static final int CLIENT_WINDOW_SIZE = 4096;
    public static final String FILE_PATH = "hehe.txt";
    public static final int TIMEOUT_MS = 1000;
    public static final int MAX_RETRIES = 15;

    public static final int FAST_RETRANSMIT_THRESHOLD = 3;
    public static final double RTT_ALPHA = 0.125;
    public static final double RTT_BETA = 0.25;

    private Constants() {
        throw new UnsupportedOperationException("This is a constants class");
    }
}
