# Graph Simulator Usage Guide

## Overview
The `graph_simulator.py` is a comprehensive tool for visualizing and analyzing your Distance Vector Routing network topology. It can show the network structure, calculate shortest paths, and provide detailed statistics.

## Installation
Make sure you have the required packages installed:
```bash
pip install -r requirements.txt
```

## Usage Examples

### 1. Basic Network Visualization
```bash
python graph_simulator.py --basic
```
This shows the basic network topology with all nodes and edges.

### 2. Network Statistics
```bash
python graph_simulator.py --stats
```
Shows detailed network statistics including:
- Number of nodes and edges
- Network diameter
- Clustering coefficient
- Node degrees

### 3. Shortest Paths Table
```bash
python graph_simulator.py --table
```
Displays a complete shortest paths table between all node pairs.

### 4. Shortest Path Between Two Nodes
```bash
python graph_simulator.py --path A G
```
Shows the shortest path from node A to node G with visualization.

### 5. All Paths From One Node
```bash
python graph_simulator.py --all-paths A
```
Shows all shortest paths from node A to all other nodes.

### 6. Interactive Mode
```bash
python graph_simulator.py --interactive
```
Starts an interactive session where you can:
- View network topology
- Explore shortest paths
- Print statistics
- Save graphs to files

### 7. Save Graphs
```bash
python graph_simulator.py --basic --save network_topology.png
python graph_simulator.py --path A G --save path_A_to_G.png
```

## Features

### Network Analysis
- **Topology Visualization**: Shows the complete network structure
- **Shortest Path Calculation**: Uses Dijkstra's algorithm to find optimal paths
- **Network Statistics**: Comprehensive analysis of network properties
- **Path Comparison**: Compare different routes between nodes

### Visualization Features
- **Node Highlighting**: Important nodes are highlighted in different colors
- **Edge Weights**: All link costs are clearly displayed
- **Path Highlighting**: Shortest paths are highlighted in different colors
- **Multiple Layouts**: Automatic layout optimization for best visualization

### Interactive Features
- **Menu-driven Interface**: Easy-to-use interactive mode
- **Real-time Analysis**: Instant calculation and visualization
- **File Export**: Save visualizations as PNG files
- **Custom Queries**: Explore specific paths and connections

## Understanding the Output

### Network Statistics
- **Nodes**: Total number of routers in the network
- **Edges**: Total number of connections
- **Diameter**: Maximum shortest path length in the network
- **Clustering**: How interconnected the network is
- **Degrees**: Number of connections each node has

### Shortest Paths Table
- Shows the minimum cost between every pair of nodes
- Useful for understanding the network's connectivity
- Helps identify potential bottlenecks

### Path Visualization
- Source nodes are highlighted in red
- Path edges are highlighted in bright colors
- Costs are displayed on edges
- Multiple paths can be shown with different colors

## Your Complex Network Analysis

Your current topology has:
- **19 nodes** (A through S)
- **35 edges** (connections)
- **Network diameter of 4** (maximum hops between any two nodes)
- **Average degree of 3.68** (average connections per node)

### Key Characteristics:
1. **Multiple Alternative Paths**: Your network has many alternative routes between nodes
2. **Balanced Connectivity**: Good distribution of connections across nodes
3. **Short Diameter**: Most nodes can reach each other in 4 hops or less
4. **High Redundancy**: Multiple paths provide fault tolerance

This design ensures that:
- The Distance Vector algorithm will need multiple iterations to converge
- There are competing paths that will be evaluated
- The network is resilient to link failures
- Routing tables will change as better paths are discovered

## Tips for Analysis

1. **Compare Direct vs. Multi-hop Paths**: 
   - Direct A→H costs 10, but A→B→H costs 9
   - This will cause routing table updates in multiple iterations

2. **Identify Bottlenecks**:
   - Node H has degree 5 (most connected)
   - Critical for network connectivity

3. **Analyze Convergence**:
   - Long chains like A→B→C→D→E→F→G will need 6 iterations to propagate
   - Cross-connections will create route updates in intermediate iterations

Run the interactive mode to explore your network in detail!
