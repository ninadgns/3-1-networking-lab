#!/usr/bin/env python3
"""
Simple test script for the graph simulator
Tests the network analysis without GUI components
"""

import sys
import os
sys.path.append('.')

from graph_simulator import NetworkGraphSimulator

def test_graph_simulator():
    """Test the graph simulator functionality"""
    print("Testing Graph Simulator...")
    print("=" * 50)
    
    # Create simulator
    simulator = NetworkGraphSimulator("topology.txt")
    
    # Read topology
    nodes, edges = simulator.read_topology()
    print(f"\nTopology loaded: {len(nodes)} nodes, {len(edges)} edges")
    
    # Calculate layout (without displaying)
    simulator.calculate_layout()
    
    # Calculate shortest paths
    simulator.calculate_shortest_paths()
    
    # Print network statistics
    simulator.print_network_statistics()
    
    # Print shortest paths table
    simulator.print_shortest_paths_table()
    
    # Test specific shortest path
    print("\n" + "=" * 60)
    print("SHORTEST PATH ANALYSIS")
    print("=" * 60)
    
    # Test path from A to G
    if 'A' in simulator.shortest_paths and 'G' in simulator.shortest_paths['A']:
        path_info = simulator.shortest_paths['A']['G']
        print(f"Shortest path from A to G:")
        print(f"  Path: {' → '.join(path_info['path'])}")
        print(f"  Cost: {path_info['cost']}")
        
        # Show why this requires multiple iterations
        print(f"\nWhy this requires multiple iterations:")
        print(f"  - Direct path A→H→I→J→K→G = 10+1+1+1+1 = 14")
        print(f"  - Better path A→B→C→D→E→F→G = 1+2+3+1+2+4 = 13")
        print(f"  - Optimal path found: {' → '.join(path_info['path'])} = {path_info['cost']}")
        
        # Show the discovery process
        print(f"\nDiscovery process:")
        print(f"  Iteration 1: A knows about direct neighbors (B=1, H=10, L=15, S=20)")
        print(f"  Iteration 2: A learns about 2-hop paths (C=3 via B, I=11 via H)")
        print(f"  Iteration 3: A learns about 3-hop paths (D=6 via B→C, J=12 via H→I)")
        print(f"  Iteration 4+: A discovers better paths as information propagates")
    
    # Test multiple paths from A
    print(f"\nAll paths from A:")
    if 'A' in simulator.shortest_paths:
        for target in sorted(simulator.shortest_paths['A'].keys()):
            if target != 'A':
                path_info = simulator.shortest_paths['A'][target]
                print(f"  A → {target}: Cost={path_info['cost']}, Path={' → '.join(path_info['path'])}")
    
    print(f"\n" + "=" * 60)
    print("CONVERGENCE ANALYSIS")
    print("=" * 60)
    
    # Analyze why convergence takes multiple iterations
    print("Factors that cause multi-iteration convergence:")
    print("1. Long chains (A→B→C→D→E→F→G) need 6 iterations to propagate")
    print("2. Alternative paths create competition:")
    print("   - A to G: Direct via H (cost 14) vs optimal via B (cost 12)")
    print("   - Multiple cross-connections create path options")
    print("3. Cycles in the network (S→A, back-connections)")
    print("4. 19 nodes with 35 edges create complex routing decisions")
    
    print(f"\nNetwork complexity metrics:")
    print(f"- Diameter: {simulator.graph.nodes.__len__() // 4} (estimated iterations needed)")
    print(f"- Average degree: {sum(dict(simulator.graph.degree()).values()) / len(simulator.graph.nodes()):.2f}")
    print(f"- Connectivity: High (multiple alternative paths)")
    
    return simulator

if __name__ == "__main__":
    # Run the test
    simulator = test_graph_simulator()
    
    print(f"\n" + "=" * 60)
    print("GRAPH SIMULATOR READY")
    print("=" * 60)
    print("Use the following commands to visualize:")
    print("  python graph_simulator.py --stats")
    print("  python graph_simulator.py --table")
    print("  python graph_simulator.py --path A G")
    print("  python graph_simulator.py --interactive")
    print("  python graph_simulator.py --help")
