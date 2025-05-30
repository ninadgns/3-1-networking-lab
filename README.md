# Networking Lab – 3rd Year 1st Semester

A collection of networking programming assignments and implementations covering various network protocols and communication patterns.

## Repository Structure

```
├── Lab2/                          # Socket Programming Basics
├── Lab3/                          # Bank Server Implementation
├── Lab4/                          # HTTP File Server
├── Lab5/                          # Network Simulation (Packet Tracer)
├── Lab6/                          # TCP/UDP Implementations
└── Two Way Communication/         # Bidirectional Client-Server
```

## Labs Overview

### Lab 2: Socket Programming Fundamentals
**Files:** [`AmioServer.java`](Lab2/AmioServer.java), [`Server.java`](Lab2/Server.java)

Basic client-server implementation demonstrating:
- Socket creation and connection handling
- Prime number checking service
- Palindrome validation service
- Basic request-response communication

### Lab 3: Bank Server
**Files:** [`BankServer.java`](Lab3/BankServer.java)

Banking application server implementation with account management capabilities.

### Lab 4: HTTP File Server
**Directories:** [`Task 1`](Lab4/Task%201/), [`Task 2`](Lab4/Task%202/)

HTTP server implementations featuring:
- File serving capabilities ([`SimpleHttpServer.java`](Lab4/Task%202/SimpleHttpServer.java))
- HTTP client implementation ([`HttpFileClient.java`](Lab4/Task%202/HttpFileClient.java))
- File directory management
- HTTP request/response handling

**Key Features:**
- Automatic directory creation for file storage
- Dynamic file listing
- HTTP protocol compliance

### Lab 5: Network Simulation
**Files:** [`prothom.pkt`](Lab5/prothom.pkt)

Cisco Packet Tracer network simulation demonstrating network topology and configuration.

### Lab 6: Advanced TCP/UDP Communication
**Files:** [`TCPClient.java`](Lab6/TCPClient.java), [`TCPServer.java`](Lab6/TCPServer.java), [`UdpClient.java`](Lab6/UdpClient.java)

#### TCP Subfolder: Comprehensive TCP Implementation
**Location:** [`Lab6/TCP/`](Lab6/TCP/)

A full-featured TCP client-server implementation with advanced networking concepts:

**Core Files:**
- [`Server.java`](Lab6/TCP/Server.java) - Multi-threaded file transfer server
- [`Client.java`](Lab6/TCP/Client.java) - Sliding window protocol client
- [`Packet.java`](Lab6/TCP/Packet.java) - Custom TCP packet implementation
- [`ClientConnectionHandler.java`](Lab6/TCP/ClientConnectionHandler.java) - Connection management
- [`Constants.java`](Lab6/TCP/Constants.java) - Configuration parameters

**TCP Features Implemented:**
- **Connection Management:** Three-way handshake, four-way termination
- **Reliability:** Timeout-based retransmission, fast retransmit (triple duplicate ACK)
- **Flow Control:** Sliding window protocol with configurable window sizes
- **Performance:** RTT estimation using EWMA, adaptive timeout calculation
- **Error Simulation:** Configurable packet loss for testing (15% default)

**RTT Calculation:**
```
EstimatedRTT = (1 - α) × EstimatedRTT + α × SampleRTT
DevRTT = (1 - β) × DevRTT + β × |SampleRTT - EstimatedRTT|
TimeoutInterval = EstimatedRTT + 4 × DevRTT
```
Where α = 0.125 and β = 0.25

### Two Way Communication
**Files:** [`client.java`](Two%20Way%20Communication/client.java), [`server.java`](Two%20Way%20Communication/server.java)

Bidirectional communication system with:
- Real-time message exchange
- Multi-threaded architecture
- Color-coded message display
- Message history management

## Getting Started

### Prerequisites
- Java Development Kit (JDK) 8 or higher
- Cisco Packet Tracer (for Lab 5)

### Running the Projects

#### For Java-based Labs:
```bash
# Navigate to the specific lab directory
cd Lab6/TCP

# Compile all Java files
javac *.java

# Start the server
java Server

# In another terminal, run the client
java Client
```

#### File Requirements:
- For TCP file transfer: Place [`hehe.txt`](Lab6/hehe.txt) in the project directory
- Received files are saved as `received_file_X.txt` where X is the client ID
- The server supports multiple concurrent client connections

## Key Learning Outcomes

- **Socket Programming:** Client-server architecture fundamentals
- **Protocol Implementation:** TCP reliability mechanisms, UDP simplicity
- **Concurrency:** Multi-threaded server design
- **Network Reliability:** Error detection, correction, and retransmission
- **Performance Optimization:** RTT estimation, flow control, congestion management
- **Real-world Applications:** File transfer, banking systems, web servers

## Testing and Debugging

The implementations include comprehensive logging for:
- Packet transmission and reception details
- RTT measurements and estimates
- Window sliding information
- Packet loss and retransmission statistics
- Fast retransmit triggers
- Connection state changes

## Configuration

Key parameters can be adjusted in [`Constants.java`](Lab6/TCP/Constants.java):
- `MAX_SEGMENT_SIZE`: 730 bytes
- `WINDOW_SIZE`: 4096 bytes
- `PACKET_LOSS_RATE`: 0.15 (15%)
- `FAST_RETRANSMIT_THRESHOLD`: 3 duplicate ACKs

## Additional Resources

- Detailed implementation documentation in [`Lab6/TCP/Readme.md`](Lab6/TCP/Readme.md)
- Lab reports and analysis in respective task folders
- UML diagrams for system architecture ([`hehe.uml`](Two%20Way%20Communication/hehe.uml))

---

*This repository represents practical implementations of fundamental networking concepts including reliable data transfer, flow control, congestion management, and real-time communication systems.*