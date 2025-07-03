import time
import random
from collections import defaultdict
import copy

class Router:
    def __init__(self, router_id):
        self.id = router_id
        self.neighbors = {}  # {neighbor_id: cost}
        self.routing_table = {}  # {dest: {'cost': cost, 'next_hop': next_hop}}
        self.distance_vector = {}  # {dest: cost}
         
    def initialize_routing_table(self, all_routers):
        """Initialize routing table with direct neighbors and infinity for others"""
        self.routing_table = {}
        self.distance_vector = {}
        
        # Cost to self is 0
        self.routing_table[self.id] = {'cost': 0, 'next_hop': self.id}
        self.distance_vector[self.id] = 0
        
        # Initialize for all routers
        for router_id in all_routers:
            if router_id != self.id:
                if router_id in self.neighbors:
                    # Direct neighbor
                    cost = self.neighbors[router_id]
                    self.routing_table[router_id] = {'cost': cost, 'next_hop': router_id}
                    self.distance_vector[router_id] = cost
                else:
                    # Not a direct neighbor - set to infinity
                    self.routing_table[router_id] = {'cost': float('inf'), 'next_hop': None}
                    self.distance_vector[router_id] = float('inf')
    
    def get_distance_vector_for_neighbor(self, neighbor_id):
        """Get distance vector to send to a specific neighbor (with poison reverse)"""
        vector = copy.deepcopy(self.distance_vector)
        
        # Apply poison reverse
        for dest, route_info in self.routing_table.items():
            if route_info['next_hop'] == neighbor_id and dest != self.id:
                vector[dest] = float('inf')
        
        return vector
    
    def update_routing_table(self, from_neighbor, received_vector):
        """Update routing table using Bellman-Ford algorithm"""
        changes_made = False
        
        neighbor_cost = self.neighbors.get(from_neighbor, float('inf'))
        
        for dest, received_cost in received_vector.items():
            if dest == self.id:
                continue
            
            # Bellman-Ford update: D_x(y) = min(D_x(y), c(x,v) + D_v(y))
            new_cost = neighbor_cost + received_cost
            current_cost = self.routing_table.get(dest, {'cost': float('inf')})['cost']
            
            if new_cost < current_cost:
                # Found a better path
                self.routing_table[dest] = {'cost': new_cost, 'next_hop': from_neighbor}
                self.distance_vector[dest] = new_cost
                changes_made = True
            elif (self.routing_table.get(dest, {}).get('next_hop') == from_neighbor and 
                  new_cost != current_cost):
                # Update existing route through this neighbor
                self.routing_table[dest] = {'cost': new_cost, 'next_hop': from_neighbor}
                self.distance_vector[dest] = new_cost
                changes_made = True
        
        return changes_made
    
    def update_neighbor_cost(self, neighbor_id, new_cost):
        """Update the cost to a direct neighbor"""
        old_cost = self.neighbors.get(neighbor_id, float('inf'))
        self.neighbors[neighbor_id] = new_cost
        
        # Update routing table for this neighbor
        if neighbor_id in self.routing_table:
            self.routing_table[neighbor_id]['cost'] = new_cost
            self.distance_vector[neighbor_id] = new_cost
            
            # Check if any routes through this neighbor need updating
            for dest, route_info in self.routing_table.items():
                if route_info['next_hop'] == neighbor_id and dest != neighbor_id:
                    # Recalculate cost through this neighbor
                    new_route_cost = new_cost + (route_info['cost'] - old_cost)
                    if new_route_cost >= 0:
                        self.routing_table[dest]['cost'] = new_route_cost
                        self.distance_vector[dest] = new_route_cost
    
    def print_routing_table(self, timestamp):
        """Print the current routing table"""
        print(f"\n[Time = {timestamp:.1f}s] Routing Table at Router {self.id}:")
        print("Dest | Cost | Next Hop")
        print("-" * 25)
        
        for dest in sorted(self.routing_table.keys()):
            route_info = self.routing_table[dest]
            cost = route_info['cost']
            next_hop = route_info['next_hop'] if route_info['next_hop'] else "None"
            
            cost_str = str(cost) if cost != float('inf') else "∞"
            print(f"{dest:4} | {cost_str:4} | {next_hop}")


class Network:
    def __init__(self, topology_file):
        self.routers = {}
        self.topology_file = topology_file
        self.message_count = 0
        self.logs = []
        self.start_time = time.time()
        self.iteration_count = 0
        
    def read_topology(self):
        """Read topology from file and initialize network"""
        edges = []
        router_ids = set()
        
        try:
            with open(self.topology_file, 'r') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#'):
                        parts = line.split()
                        if len(parts) >= 3:
                            router1, router2, cost = parts[0], parts[1], int(parts[2])
                            edges.append((router1, router2, cost))
                            router_ids.add(router1)
                            router_ids.add(router2)
        except FileNotFoundError:
            # Create a sample topology if file doesn't exist
            print(f"Topology file {self.topology_file} not found. Creating sample topology...")
            self.create_sample_topology()
            return self.read_topology()
        
        # Create routers
        for router_id in router_ids:
            self.routers[router_id] = Router(router_id)
        
        # Set up neighbors and costs
        for router1, router2, cost in edges:
            self.routers[router1].neighbors[router2] = cost
            self.routers[router2].neighbors[router1] = cost
        
        # Initialize routing tables
        for router in self.routers.values():
            router.initialize_routing_table(list(router_ids))
        
        print(f"Network initialized with {len(self.routers)} routers")
        return edges
    
    def create_sample_topology(self):
        """Create a sample topology file"""
        sample_topology = """A B 2
A C 5
B C 1
B D 3
C D 2"""
        
        with open(self.topology_file, 'w') as f:
            f.write(sample_topology)
        print(f"Created sample topology file: {self.topology_file}")
    
    def send_distance_vectors(self):
        """Each router sends its distance vector to all neighbors"""
        messages_sent = 0
        any_changes = False
        
        # Collect all messages to be sent first
        messages = []
        
        for router in self.routers.values():
            for neighbor_id in router.neighbors.keys():
                if neighbor_id in self.routers:
                    vector = router.get_distance_vector_for_neighbor(neighbor_id)
                    messages.append((router.id, neighbor_id, vector))
        
        # Process all messages at once
        for sender_id, receiver_id, vector in messages:
            receiver_router = self.routers[receiver_id]
            changes = receiver_router.update_routing_table(sender_id, vector)
            messages_sent += 1
            
            if changes:
                any_changes = True
        
        self.message_count += messages_sent
        return messages_sent, any_changes
    
    def update_random_link_cost(self):
        """Randomly update the cost of one link in the network"""
        if not self.routers:
            return False
        
        # Get all edges
        edges = []
        for router_id, router in self.routers.items():
            for neighbor_id, cost in router.neighbors.items():
                if router_id < neighbor_id:  # Avoid duplicates
                    edges.append((router_id, neighbor_id, cost))
        
        if not edges:
            return False
        
        # Pick a random edge
        router1_id, router2_id, old_cost = random.choice(edges)
        
        # Generate new cost (between 1 and 10)
        new_cost = random.randint(1, 10)
        while new_cost == old_cost:
            new_cost = random.randint(1, 10)
        
        # Update both routers
        self.routers[router1_id].update_neighbor_cost(router2_id, new_cost)
        self.routers[router2_id].update_neighbor_cost(router1_id, new_cost)
        
        timestamp = time.time() - self.start_time
        print(f"\n[Time = {timestamp:.1f}s] Cost updated: {router1_id} <-> {router2_id} changed from {old_cost} to {new_cost}")
        
        self.logs.append(f"Time {timestamp:.1f}s: Link {router1_id}-{router2_id} cost changed from {old_cost} to {new_cost}")
        return True
    
    def print_all_routing_tables(self, timestamp, title="Routing Tables"):
        """Print routing tables for all routers"""
        print(f"\n{'='*60}")
        print(f"{title} at Time = {timestamp:.1f}s")
        print(f"{'='*60}")
        
        for router in sorted(self.routers.values(), key=lambda r: r.id):
            router.print_routing_table(timestamp)
    
    def print_initial_tables(self):
        """Print initial routing tables for all routers"""
        self.print_all_routing_tables(0.0, "INITIAL ROUTING TABLES")
    
    def print_final_shortest_paths(self):
        """Print final shortest paths from each router to all others"""
        print("\n" + "=" * 60)
        print("FINAL ALL-PAIR SHORTEST PATHS")
        print("=" * 60)
        
        for router in sorted(self.routers.values(), key=lambda r: r.id):
            print(f"\nShortest paths from Router {router.id}:")
            for dest in sorted(router.routing_table.keys()):
                if dest != router.id:
                    route_info = router.routing_table[dest]
                    cost = route_info['cost']
                    next_hop = route_info['next_hop']
                    cost_str = str(cost) if cost != float('inf') else "∞"
                    print(f"  To {dest}: Cost = {cost_str}, Next Hop = {next_hop}")
    
    def print_statistics(self):
        """Print simulation statistics"""
        elapsed_time = time.time() - self.start_time
        print(f"\n" + "=" * 60)
        print("SIMULATION STATISTICS")
        print("=" * 60)
        print(f"Total simulation time: {elapsed_time:.1f} seconds")
        print(f"Total iterations: {self.iteration_count}")
        print(f"Total messages exchanged: {self.message_count}")
        print(f"Number of cost updates: {len(self.logs)}")
        if elapsed_time > 0:
            print(f"Average messages per second: {self.message_count/elapsed_time:.2f}")
        print(f"Average messages per iteration: {self.message_count/max(1, self.iteration_count):.2f}")
    
    def check_convergence(self, max_iterations=5):
        """Check if network has converged by running updates without changes"""
        for i in range(max_iterations):
            messages_sent, changes = self.send_distance_vectors()
            if not changes:
                timestamp = time.time() - self.start_time
                print(f"\n[Time = {timestamp:.1f}s] Network converged after {i+1} iterations. No more changes.")
                return True
        return False
    
    def run_simulation(self, duration=120, update_interval=5, cost_update_interval=30):
        """Run the distance vector routing simulation without threads"""
        print("Starting Distance Vector Routing Simulation...")
        print(f"Simulation will run for {duration} seconds")
        print(f"Distance vector updates every {update_interval} seconds")
        print(f"Cost updates every {cost_update_interval} seconds")
        
        # Read topology and initialize
        edges = self.read_topology()
        print(f"Topology: {edges}")
        
        # Print initial routing tables
        self.print_initial_tables()
        
        # Main simulation loop
        last_update_time = 0
        last_cost_update_time = 0
        
        try:
            while True:
                current_time = time.time() - self.start_time
                
                # Check if simulation duration is reached
                if current_time >= duration:
                    break
                
                # Periodic distance vector updates
                if current_time - last_update_time >= update_interval:
                    self.iteration_count += 1
                    messages_sent, changes_made = self.send_distance_vectors()
                    
                    if changes_made:
                        self.print_all_routing_tables(current_time, f"UPDATED ROUTING TABLES (Iteration {self.iteration_count})")
                        print(f"\n[Time = {current_time:.1f}s] Iteration {self.iteration_count}: {messages_sent} messages sent, tables updated")
                        
                        # Check for convergence after changes
                        self.check_convergence(3)
                    else:
                        print(f"\n[Time = {current_time:.1f}s] Iteration {self.iteration_count}: {messages_sent} messages sent, no changes (converged)")
                    
                    last_update_time = current_time
                
                # Periodic cost updates
                if current_time - last_cost_update_time >= cost_update_interval:
                    cost_changed = self.update_random_link_cost()
                    if cost_changed:
                        # Immediately send updates after cost change
                        messages_sent, changes_made = self.send_distance_vectors()
                        if changes_made:
                            self.print_all_routing_tables(current_time, "ROUTING TABLES AFTER COST CHANGE")
                            print(f"\n[Time = {current_time:.1f}s] Cost change triggered {messages_sent} messages")
                            
                            # Check convergence after cost change
                            print(f"\n[Time = {current_time:.1f}s] Checking convergence after cost change...")
                            converged = self.check_convergence(10)
                            if converged:
                                conv_time = time.time() - self.start_time
                                print(f"[Time = {conv_time:.1f}s] Convergence complete after cost change.")
                    
                    last_cost_update_time = current_time
                
                # Small sleep to prevent busy waiting
                time.sleep(0.1)
                
        except KeyboardInterrupt:
            print("\nSimulation interrupted by user")
        
        # Final convergence check
        print(f"\n{'='*60}")
        print("FINAL CONVERGENCE CHECK")
        print(f"{'='*60}")
        self.check_convergence(10)
        
        # Print final results
        timestamp = time.time() - self.start_time
        self.print_all_routing_tables(timestamp, "FINAL ROUTING TABLES")
        self.print_final_shortest_paths()
        self.print_statistics()
        
        print(f"\nSimulation completed!")


def main():
    """Main function to run the simulation"""
    print("Distance Vector Routing Algorithm Simulation")
    print("=" * 60)
    
    # Create and run simulation
    network = Network("topology.txt")
    
    # Run simulation: 90 seconds total, updates every 5s, cost changes every 30s
    network.run_simulation(duration=90, update_interval=5, cost_update_interval=30)


if __name__ == "__main__":
    main()