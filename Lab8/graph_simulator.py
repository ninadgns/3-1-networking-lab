#!/usr/bin/env python3
"""
Graph Simulator for Distance Vector Routing Network
Visualizes the network topology and routing paths
"""

import networkx as nx
import matplotlib.pyplot as plt
import matplotlib.patches as patches
from matplotlib.animation import FuncAnimation
import numpy as np
import argparse
import sys
import time
from collections import defaultdict

class NetworkGraphSimulator:
    def __init__(self, topology_file="topology.txt"):
        self.topology_file = topology_file
        self.graph = nx.Graph()
        self.pos = None
        self.node_colors = {}
        self.edge_colors = {}
        self.shortest_paths = {}
        self.routing_tables = {}
        
    def read_topology(self):
        """Read network topology from file"""
        print(f"Reading topology from {self.topology_file}...")
        edges = []
        nodes = set()
        
        try:
            with open(self.topology_file, 'r') as f:
                for line_num, line in enumerate(f, 1):
                    line = line.strip()
                    if line and not line.startswith('#'):
                        parts = line.split()
                        if len(parts) >= 3:
                            node1, node2, cost = parts[0], parts[1], int(parts[2])
                            edges.append((node1, node2, cost))
                            nodes.add(node1)
                            nodes.add(node2)
                            print(f"  {node1} <-> {node2} (cost: {cost})")
        except FileNotFoundError:
            print(f"Error: Topology file '{self.topology_file}' not found!")
            sys.exit(1)
        
        # Add nodes and edges to NetworkX graph
        for node in nodes:
            self.graph.add_node(node)
        
        for node1, node2, cost in edges:
            self.graph.add_edge(node1, node2, weight=cost)
        
        print(f"Graph created with {len(nodes)} nodes and {len(edges)} edges")
        return nodes, edges
    
    def calculate_layout(self):
        """Calculate optimal node positions for visualization"""
        print("Calculating optimal layout...")
        
        # Try different layout algorithms (avoiding ones that need scipy)
        try:
            # Try spring layout first (most commonly used)
            self.pos = nx.spring_layout(self.graph, k=3, iterations=50)
            print(f"Using spring layout for {len(self.graph.nodes())} nodes")
        except Exception as e:
            print(f"Spring layout failed: {e}")
            try:
                # Fallback to circular layout
                self.pos = nx.circular_layout(self.graph)
                print(f"Using circular layout for {len(self.graph.nodes())} nodes")
            except Exception as e:
                print(f"Circular layout failed: {e}")
                # Ultimate fallback - random layout
                self.pos = nx.random_layout(self.graph)
                print(f"Using random layout for {len(self.graph.nodes())} nodes")
        
        # If the graph is large, try to optimize the layout
        if len(self.graph.nodes()) > 15:
            try:
                # For large graphs, use a more spread out layout
                self.pos = nx.spring_layout(self.graph, k=5, iterations=100)
                print("Optimized layout for large graph")
            except Exception:
                pass  # Keep the existing layout
    
    def calculate_shortest_paths(self):
        """Calculate shortest paths between all pairs of nodes"""
        print("Calculating shortest paths...")
        self.shortest_paths = {}
        
        for source in self.graph.nodes():
            self.shortest_paths[source] = {}
            try:
                # Use Dijkstra's algorithm
                paths = nx.single_source_dijkstra_path(self.graph, source, weight='weight')
                costs = nx.single_source_dijkstra_path_length(self.graph, source, weight='weight')
                
                for target in self.graph.nodes():
                    if target in paths:
                        self.shortest_paths[source][target] = {
                            'path': paths[target],
                            'cost': costs[target]
                        }
            except nx.NetworkXNoPath:
                print(f"Warning: No path from {source} to some nodes")
    
    def draw_basic_graph(self, title="Network Topology", save_file=None):
        """Draw the basic network graph"""
        plt.figure(figsize=(14, 10))
        
        # Draw nodes
        nx.draw_networkx_nodes(self.graph, self.pos, 
                             node_color='lightblue', 
                             node_size=1000, 
                             alpha=0.8)
        
        # Draw edges with weights
        nx.draw_networkx_edges(self.graph, self.pos, 
                             edge_color='gray', 
                             width=2, 
                             alpha=0.6)
        
        # Draw node labels
        nx.draw_networkx_labels(self.graph, self.pos, 
                              font_size=12, 
                              font_weight='bold')
        
        # Draw edge labels (costs)
        edge_labels = nx.get_edge_attributes(self.graph, 'weight')
        nx.draw_networkx_edge_labels(self.graph, self.pos, 
                                   edge_labels, 
                                   font_size=10)
        
        plt.title(title, fontsize=16, fontweight='bold')
        plt.axis('off')
        plt.tight_layout()
        
        if save_file:
            plt.savefig(save_file, dpi=300, bbox_inches='tight')
            print(f"Graph saved to {save_file}")
        
        plt.show()
    
    def draw_shortest_path(self, source, target, save_file=None):
        """Draw the shortest path between two nodes"""
        if source not in self.shortest_paths or target not in self.shortest_paths[source]:
            print(f"No path found from {source} to {target}")
            return
        
        path_info = self.shortest_paths[source][target]
        path = path_info['path']
        cost = path_info['cost']
        
        plt.figure(figsize=(14, 10))
        
        # Draw all nodes
        node_colors = ['red' if node in path else 'lightblue' for node in self.graph.nodes()]
        nx.draw_networkx_nodes(self.graph, self.pos, 
                             node_color=node_colors, 
                             node_size=1000, 
                             alpha=0.8)
        
        # Draw all edges
        nx.draw_networkx_edges(self.graph, self.pos, 
                             edge_color='lightgray', 
                             width=1, 
                             alpha=0.3)
        
        # Highlight path edges
        path_edges = [(path[i], path[i+1]) for i in range(len(path)-1)]
        nx.draw_networkx_edges(self.graph, self.pos, 
                             edgelist=path_edges,
                             edge_color='red', 
                             width=4, 
                             alpha=0.8)
        
        # Draw labels
        nx.draw_networkx_labels(self.graph, self.pos, 
                              font_size=12, 
                              font_weight='bold')
        
        # Draw edge weights
        edge_labels = nx.get_edge_attributes(self.graph, 'weight')
        nx.draw_networkx_edge_labels(self.graph, self.pos, 
                                   edge_labels, 
                                   font_size=10)
        
        plt.title(f"Shortest Path: {source} → {target}\nPath: {' → '.join(path)}\nTotal Cost: {cost}", 
                 fontsize=14, fontweight='bold')
        plt.axis('off')
        plt.tight_layout()
        
        if save_file:
            plt.savefig(save_file, dpi=300, bbox_inches='tight')
            print(f"Path visualization saved to {save_file}")
        
        plt.show()
    
    def draw_all_shortest_paths_from_node(self, source, save_file=None):
        """Draw all shortest paths from a specific node"""
        if source not in self.shortest_paths:
            print(f"No paths calculated from {source}")
            return
        
        plt.figure(figsize=(16, 12))
        
        # Create a color map for different destinations
        targets = [t for t in self.shortest_paths[source].keys() if t != source]
        colors = plt.cm.Set3(np.linspace(0, 1, len(targets)))
        
        # Draw all nodes
        nx.draw_networkx_nodes(self.graph, self.pos, 
                             node_color='lightblue', 
                             node_size=1000, 
                             alpha=0.8)
        
        # Draw all edges lightly
        nx.draw_networkx_edges(self.graph, self.pos, 
                             edge_color='lightgray', 
                             width=1, 
                             alpha=0.3)
        
        # Draw paths with different colors
        legend_elements = []
        for i, target in enumerate(targets):
            if target in self.shortest_paths[source]:
                path_info = self.shortest_paths[source][target]
                path = path_info['path']
                cost = path_info['cost']
                
                path_edges = [(path[j], path[j+1]) for j in range(len(path)-1)]
                nx.draw_networkx_edges(self.graph, self.pos, 
                                     edgelist=path_edges,
                                     edge_color=[colors[i]], 
                                     width=3, 
                                     alpha=0.7)
                
                # Add to legend
                legend_elements.append(plt.Line2D([0], [0], color=colors[i], lw=3, 
                                               label=f"{target} (cost: {cost})"))
        
        # Highlight source node
        nx.draw_networkx_nodes(self.graph, self.pos, 
                             nodelist=[source],
                             node_color='red', 
                             node_size=1200, 
                             alpha=0.9)
        
        # Draw labels
        nx.draw_networkx_labels(self.graph, self.pos, 
                              font_size=12, 
                              font_weight='bold')
        
        # Draw edge weights
        edge_labels = nx.get_edge_attributes(self.graph, 'weight')
        nx.draw_networkx_edge_labels(self.graph, self.pos, 
                                   edge_labels, 
                                   font_size=8)
        
        plt.title(f"All Shortest Paths from Node {source}", 
                 fontsize=16, fontweight='bold')
        plt.legend(handles=legend_elements, loc='upper left', bbox_to_anchor=(1.05, 1))
        plt.axis('off')
        plt.tight_layout()
        
        if save_file:
            plt.savefig(save_file, dpi=300, bbox_inches='tight')
            print(f"All paths visualization saved to {save_file}")
        
        plt.show()
    
    def print_network_statistics(self):
        """Print network statistics"""
        print("\n" + "="*60)
        print("NETWORK STATISTICS")
        print("="*60)
        
        print(f"Number of nodes: {len(self.graph.nodes())}")
        print(f"Number of edges: {len(self.graph.edges())}")
        print(f"Network diameter: {nx.diameter(self.graph)}")
        print(f"Average clustering coefficient: {nx.average_clustering(self.graph):.3f}")
        print(f"Is connected: {nx.is_connected(self.graph)}")
        
        # Node degrees
        degrees = dict(self.graph.degree())
        print(f"\nNode degrees:")
        for node in sorted(degrees.keys()):
            print(f"  {node}: {degrees[node]}")
        
        print(f"\nAverage degree: {np.mean(list(degrees.values())):.2f}")
        print(f"Max degree: {max(degrees.values())}")
        print(f"Min degree: {min(degrees.values())}")
    
    def print_shortest_paths_table(self):
        """Print shortest paths table"""
        print("\n" + "="*80)
        print("SHORTEST PATHS TABLE")
        print("="*80)
        
        nodes = sorted(self.graph.nodes())
        
        # Print header
        print("From\\To", end="")
        for target in nodes:
            print(f"{target:>8}", end="")
        print()
        
        # Print separator
        print("-" * (8 + 8 * len(nodes)))
        
        # Print paths
        for source in nodes:
            print(f"{source:>7}", end="")
            for target in nodes:
                if source == target:
                    print(f"{'0':>8}", end="")
                elif target in self.shortest_paths[source]:
                    cost = self.shortest_paths[source][target]['cost']
                    print(f"{cost:>8}", end="")
                else:
                    print(f"{'∞':>8}", end="")
            print()
    
    def interactive_mode(self):
        """Interactive mode for exploring the graph"""
        print("\n" + "="*60)
        print("INTERACTIVE GRAPH EXPLORER")
        print("="*60)
        
        while True:
            print("\nOptions:")
            print("1. View basic network topology")
            print("2. Show shortest path between two nodes")
            print("3. Show all paths from a node")
            print("4. Print network statistics")
            print("5. Print shortest paths table")
            print("6. Save current graph to file")
            print("7. Exit")
            
            choice = input("\nEnter your choice (1-7): ").strip()
            
            if choice == '1':
                self.draw_basic_graph()
            
            elif choice == '2':
                nodes = sorted(self.graph.nodes())
                print(f"Available nodes: {nodes}")
                source = input("Enter source node: ").strip().upper()
                target = input("Enter target node: ").strip().upper()
                
                if source in nodes and target in nodes:
                    self.draw_shortest_path(source, target)
                else:
                    print("Invalid nodes!")
            
            elif choice == '3':
                nodes = sorted(self.graph.nodes())
                print(f"Available nodes: {nodes}")
                source = input("Enter source node: ").strip().upper()
                
                if source in nodes:
                    self.draw_all_shortest_paths_from_node(source)
                else:
                    print("Invalid node!")
            
            elif choice == '4':
                self.print_network_statistics()
            
            elif choice == '5':
                self.print_shortest_paths_table()
            
            elif choice == '6':
                filename = input("Enter filename (default: network_topology.png): ").strip()
                if not filename:
                    filename = "network_topology.png"
                self.draw_basic_graph(save_file=filename)
            
            elif choice == '7':
                print("Goodbye!")
                break
            
            else:
                print("Invalid choice! Please try again.")


def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Network Graph Simulator for Distance Vector Routing")
    parser.add_argument("--topology", "-t", default="topology.txt", 
                       help="Topology file (default: topology.txt)")
    parser.add_argument("--interactive", "-i", action="store_true",
                       help="Run in interactive mode")
    parser.add_argument("--basic", "-b", action="store_true",
                       help="Just show basic topology")
    parser.add_argument("--path", "-p", nargs=2, metavar=('SOURCE', 'TARGET'),
                       help="Show shortest path between two nodes")
    parser.add_argument("--all-paths", "-a", metavar='SOURCE',
                       help="Show all shortest paths from a node")
    parser.add_argument("--stats", "-s", action="store_true",
                       help="Print network statistics")
    parser.add_argument("--table", action="store_true",
                       help="Print shortest paths table")
    parser.add_argument("--save", metavar='FILENAME',
                       help="Save graph to file")
    
    args = parser.parse_args()
    
    # Create simulator
    simulator = NetworkGraphSimulator(args.topology)
    
    # Read topology and calculate layout
    nodes, edges = simulator.read_topology()
    simulator.calculate_layout()
    simulator.calculate_shortest_paths()
    
    # Execute based on arguments
    if args.interactive:
        simulator.interactive_mode()
    elif args.basic:
        simulator.draw_basic_graph(save_file=args.save)
    elif args.path:
        source, target = args.path[0].upper(), args.path[1].upper()
        simulator.draw_shortest_path(source, target, save_file=args.save)
    elif args.all_paths:
        source = args.all_paths.upper()
        simulator.draw_all_shortest_paths_from_node(source, save_file=args.save)
    elif args.stats:
        simulator.print_network_statistics()
    elif args.table:
        simulator.print_shortest_paths_table()
    else:
        # Default: show basic topology and enter interactive mode
        simulator.draw_basic_graph(save_file=args.save)
        print("\nStarting interactive mode...")
        simulator.interactive_mode()


if __name__ == "__main__":
    main()
