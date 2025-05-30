# TCP File Transfer

A comprehensive TCP client-server implementation for reliable file transfer with advanced TCP features.

## Files
- `Server.java` - Multi-threaded server that receives files
- `Client.java` - Client that sends files with sliding window protocol
- `Packet.java` - Custom TCP packet implementation
- `ClientConnectionHandler.java` - Handles individual client connections
- `UnackedPacket.java` - Tracks unacknowledged packets for reliability
- `Constants.java` - Configuration constants

## How to Run

1. Compile all Java files:
```bash
javac *.java
```

2. Start the server:
```bash
java Server
```

3. Run the client (make sure `hehe.txt` exists in the same directory):
```bash
java Client
```

## TCP Features Implemented

### Core Protocol Features
- **Three-way handshake** - Proper connection establishment (SYN, SYN-ACK, ACK)
- **Four-way handshake** - Graceful connection termination (FIN, FIN-ACK, ACK)
- **Sliding window protocol** - Flow control with configurable window sizes
- **Cumulative acknowledgments** - ACKs indicate highest sequence number received in order

### Reliability & Error Recovery
- **Packet loss simulation** - Configurable packet drop rate (15% by default)
- **Timeout-based retransmission** - Automatic packet retransmission on timeout
- **Fast retransmit** - Triple duplicate ACK detection for immediate retransmission
- **Out-of-order packet handling** - Buffering and reordering of packets

### Performance Optimization
- **RTT estimation** - Exponential Weighted Moving Average (EWMA) for RTT calculation
- **Adaptive timeout** - Dynamic timeout adjustment based on RTT estimates
- **Congestion control** - Window-based flow control mechanism

## Configuration

Key parameters in `Constants.java`:
- `MAX_SEGMENT_SIZE`: 730 bytes (packet payload size)
- `CLIENT_WINDOW_SIZE`: 4096 bytes (client receive window)
- `WINDOW_SIZE`: 4096 bytes (server receive window)
- `PACKET_LOSS_RATE`: 0.15 (15% simulated packet loss)
- `FAST_RETRANSMIT_THRESHOLD`: 3 duplicate ACKs
- `RTT_ALPHA`: 0.125 (EWMA smoothing factor for RTT)
- `RTT_BETA`: 0.25 (EWMA smoothing factor for RTT deviation)

## RTT Calculation

The implementation uses TCP's standard RTT estimation algorithm:

```
EstimatedRTT = (1 - α) × EstimatedRTT + α × SampleRTT
DevRTT = (1 - β) × DevRTT + β × |SampleRTT - EstimatedRTT|
TimeoutInterval = EstimatedRTT + 4 × DevRTT
```

Where α = 0.125 and β = 0.25

## Output Features

The implementation provides detailed logging including:
- Packet transmission and reception details
- RTT measurements and estimates
- Window sliding information
- Packet loss and retransmission statistics
- Fast retransmit triggers
- File transfer progress and completion status

## File Requirements

- Place the file to transfer as `hehe.txt` in the project directory
- Received files are saved as `received_file_X.txt` where X is the client ID
- The server supports multiple concurrent client connections

## Testing

The implementation includes built-in packet loss simulation to test reliability features. Monitor the console output to observe:
- Normal packet transmission and acknowledgment
- Timeout-based retransmissions
- Fast retransmit events
- RTT estimation updates
- Window flow control behavior