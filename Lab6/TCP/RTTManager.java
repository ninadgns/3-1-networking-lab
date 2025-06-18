public class RTTManager {
    private double estimatedRTT = 1000.0;
    private double devRTT = 0.0;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;
    
    public void updateRTTEstimates(double sampleRTT) {
        if (estimatedRTT == 1000.0) {
            // First sample
            estimatedRTT = sampleRTT;
            devRTT = sampleRTT / 2.0;
        } else {
            // EWMA calculations
            devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
        }
        
        System.out.println("RTT Update - Sample: " + String.format("%.2f", sampleRTT) + 
                          "ms, Estimated: " + String.format("%.2f", estimatedRTT) + 
                          "ms, Dev: " + String.format("%.2f", devRTT) + "ms");
    }
    
    public long calculateTimeoutInterval() {
        double timeoutInterval = estimatedRTT + 4 * devRTT;
        // Ensure minimum timeout of 100ms and maximum of 5000ms
        return Math.max(100, Math.min(5000, (long) timeoutInterval));
    }
    
    public double getEstimatedRTT() {
        return estimatedRTT;
    }
    
    public double getDevRTT() {
        return devRTT;
    }
}