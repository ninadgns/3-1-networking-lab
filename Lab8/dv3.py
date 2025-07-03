import time
import random
from collections import defaultdict
import copy
import logging
from datetime import datetime

class Router:
    def __init__(self, router_id, verbose=False, log_file=None):
        self.id = router_id
        self.neighbors = {}  # {neighbor_id: cost}
        self.routing_table = {}  # {dest: {'cost': cost, 'next_hop': next_hop}}
        self.distance_vector = {}  # {dest: cost}
        self.verbose = verbose
        self.log_file = log_file
        self.messages_sent = 0  # Track messages sent by this router
        self.messages_received = 0  # Track messages received by this router
        
    def log(self, message, level="INFO"):
        """Log messages with timestamp and router ID"""
        timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        prefix = f"[{timestamp}] Router {self.id} [{level}]:"
        log_message = f"{prefix} {message}"
        
        if self.verbose:
            if self.log_file:
                with open(self.log_file, 'a') as f:
                    f.write(log_message + '\n')
            else:
                print(log_message)
        
    def initialize_routing_table(self, all_routers):
        """Initialize routing table with direct neighbors and infinity for others"""
        self.log("Initializing routing table...")
        self.routing_table = {}
        self.distance_vector = {}
        
        # Cost to self is 0
        self.routing_table[self.id] = {'cost': 0, 'next_hop': self.id}
        self.distance_vector[self.id] = 0
        self.log(f"Self-route: {self.id} -> cost=0, next_hop={self.id}")
        
        # Initialize for all routers
        for router_id in all_routers:
            if router_id != self.id:
                if router_id in self.neighbors:
                    # Direct neighbor
                    cost = self.neighbors[router_id]
                    self.routing_table[router_id] = {'cost': cost, 'next_hop': router_id}
                    self.distance_vector[router_id] = cost
                    self.log(f"Direct neighbor: {router_id} -> cost={cost}, next_hop={router_id}")
                else:
                    # Not a direct neighbor - set to infinity
                    self.routing_table[router_id] = {'cost': float('inf'), 'next_hop': None}
                    self.distance_vector[router_id] = float('inf')
                    self.log(f"Non-neighbor: {router_id} -> cost=∞, next_hop=None")
        
        self.log(f"Routing table initialized with {len(self.routing_table)} entries")
    
    def get_distance_vector_for_neighbor(self, neighbor_id):
        """Get distance vector to send to a specific neighbor (with poison reverse)"""
        vector = copy.deepcopy(self.distance_vector)
        poison_reverse_applied = []
        
        # Apply poison reverse
        for dest, route_info in self.routing_table.items():
            if route_info['next_hop'] == neighbor_id and dest != self.id:
                vector[dest] = float('inf')
                poison_reverse_applied.append(dest)
        
        if poison_reverse_applied and self.verbose:
            self.log(f"Poison reverse to {neighbor_id}: {poison_reverse_applied} -> ∞")
        
        # Increment message count for this router
        self.messages_sent += 1
        self.log(f"Sending distance vector to {neighbor_id}: {dict(vector)} (Message #{self.messages_sent})")
        return vector
    
    def update_routing_table(self, from_neighbor, received_vector):
        """Update routing table using Bellman-Ford algorithm"""
        # Increment message received count
        self.messages_received += 1
        self.log(f"Processing distance vector from {from_neighbor}: {dict(received_vector)} (Message #{self.messages_received} received)")
        changes_made = False
        updates_log = []
        
        neighbor_cost = self.neighbors.get(from_neighbor, float('inf'))
        self.log(f"Direct cost to {from_neighbor}: {neighbor_cost}")
        
        for dest, received_cost in received_vector.items():
            if dest == self.id:
                continue
            
            # Bellman-Ford update: D_x(y) = min(D_x(y), c(x,v) + D_v(y))
            new_cost = neighbor_cost + received_cost
            current_route = self.routing_table.get(dest, {'cost': float('inf'), 'next_hop': None})
            current_cost = current_route['cost']
            current_next_hop = current_route['next_hop']
            
            self.log(f"  Dest {dest}: current_cost={current_cost}, new_cost_via_{from_neighbor}={new_cost}")
            
            if new_cost < current_cost:
                # Found a better path
                old_next_hop = current_next_hop
                self.routing_table[dest] = {'cost': new_cost, 'next_hop': from_neighbor}
                self.distance_vector[dest] = new_cost
                changes_made = True
                updates_log.append(f"BETTER PATH to {dest}: {current_cost} -> {new_cost} via {from_neighbor} (was via {old_next_hop})")
                
            elif (current_next_hop == from_neighbor and new_cost != current_cost):
                # Update existing route through this neighbor
                self.routing_table[dest] = {'cost': new_cost, 'next_hop': from_neighbor}
                self.distance_vector[dest] = new_cost
                changes_made = True
                updates_log.append(f"ROUTE UPDATE to {dest}: {current_cost} -> {new_cost} via {from_neighbor}")
            
            else:
                self.log(f"  No change for dest {dest}")
        
        if updates_log:
            for update in updates_log:
                self.log(update, "UPDATE")
        else:
            self.log("No routing table changes from this update")
        
        return changes_made
    
    def update_neighbor_cost(self, neighbor_id, new_cost):
        """Update the cost to a direct neighbor"""
        old_cost = self.neighbors.get(neighbor_id, float('inf'))
        self.log(f"Updating neighbor cost: {neighbor_id} from {old_cost} to {new_cost}")
        
        self.neighbors[neighbor_id] = new_cost
        
        # Update routing table for this neighbor
        if neighbor_id in self.routing_table:
            self.routing_table[neighbor_id]['cost'] = new_cost
            self.distance_vector[neighbor_id] = new_cost
            self.log(f"Updated direct route to {neighbor_id}: cost={new_cost}")
            
            # Check if any routes through this neighbor need updating
            routes_updated = []
            for dest, route_info in self.routing_table.items():
                if route_info['next_hop'] == neighbor_id and dest != neighbor_id:
                    old_route_cost = route_info['cost']
                    # Recalculate cost through this neighbor
                    new_route_cost = new_cost + (route_info['cost'] - old_cost)
                    if new_route_cost >= 0:
                        self.routing_table[dest]['cost'] = new_route_cost
                        self.distance_vector[dest] = new_route_cost
                        routes_updated.append(f"{dest}: {old_route_cost} -> {new_route_cost}")
            
            if routes_updated:
                self.log(f"Cascaded updates via {neighbor_id}: {routes_updated}")
    
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
    def __init__(self, topology_file, verbose=False, log_file=None):
        self.routers = {}
        self.topology_file = topology_file
        self.message_count = 0
        self.logs = []
        self.start_time = time.time()
        self.iteration_count = 0
        self.verbose = verbose
        self.log_file = log_file
        
        # Initialize log file if specified
        if self.log_file:
            with open(self.log_file, 'w') as f:
                f.write(f"Distance Vector Routing Simulation Log\n")
                f.write(f"Started at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
                f.write(f"Topology file: {self.topology_file}\n")
                f.write("=" * 60 + "\n\n")
        
    def log(self, message, level="INFO"):
        """Log network-level messages"""
        timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        prefix = f"[{timestamp}] NETWORK [{level}]:"
        log_message = f"{prefix} {message}"
        
        if self.verbose:
            if self.log_file:
                with open(self.log_file, 'a') as f:
                    f.write(log_message + '\n')
            else:
                print(log_message)
        
    def read_topology(self):
        """Read topology from file and initialize network"""
        self.log("Reading network topology...")
        edges = []
        router_ids = set()
        
        try:
            with open(self.topology_file, 'r') as f:
                for line_num, line in enumerate(f, 1):
                    line = line.strip()
                    if line and not line.startswith('#'):
                        parts = line.split()
                        if len(parts) >= 3:
                            router1, router2, cost = parts[0], parts[1], int(parts[2])
                            edges.append((router1, router2, cost))
                            router_ids.add(router1)
                            router_ids.add(router2)
                            self.log(f"Line {line_num}: {router1} <-> {router2} cost={cost}")
                        else:
                            self.log(f"Line {line_num}: Invalid format - {line}", "WARNING")
        except FileNotFoundError:
            # Create a sample topology if file doesn't exist
            self.log(f"Topology file {self.topology_file} not found. Creating sample topology...", "WARNING")
            self.create_sample_topology()
            return self.read_topology()
        
        self.log(f"Found {len(router_ids)} routers: {sorted(router_ids)}")
        self.log(f"Found {len(edges)} edges")
        
        # Create routers
        for router_id in router_ids:
            self.routers[router_id] = Router(router_id, self.verbose, self.log_file)
            self.log(f"Created router {router_id}")
        
        # Set up neighbors and costs
        for router1, router2, cost in edges:
            self.routers[router1].neighbors[router2] = cost
            self.routers[router2].neighbors[router1] = cost
            self.log(f"Established bidirectional link: {router1} <-> {router2} cost={cost}")
        
        # Log neighbor relationships
        for router_id, router in self.routers.items():
            neighbors = list(router.neighbors.keys())
            self.log(f"Router {router_id} neighbors: {neighbors}")
        
        # Initialize routing tables
        self.log("Initializing routing tables for all routers...")
        for router in self.routers.values():
            router.initialize_routing_table(list(router_ids))
        
        self.log(f"Network initialization complete: {len(self.routers)} routers, {len(edges)} links")
        return edges
    
    def create_sample_topology(self):
        """Create a sample topology file"""
        sample_topology = """# Sample network topology
# Format: RouterA RouterB Cost
A B 2
A C 5
B C 1
B D 3
C D 2"""
        
        with open(self.topology_file, 'w') as f:
            f.write(sample_topology)
        self.log(f"Created sample topology file: {self.topology_file}")
    
    def send_distance_vectors(self):
        """Each router sends its distance vector to all neighbors"""
        self.log("=== DISTANCE VECTOR EXCHANGE ROUND START ===")
        messages_sent = 0
        any_changes = False
        router_message_counts = {}  # Track messages per router
        
        # Initialize message counts for all routers
        for router_id in self.routers.keys():
            router_message_counts[router_id] = 0
        
        # Collect all messages to be sent first
        messages = []
        
        for router in self.routers.values():
            for neighbor_id in router.neighbors.keys():
                if neighbor_id in self.routers:
                    vector = router.get_distance_vector_for_neighbor(neighbor_id)
                    messages.append((router.id, neighbor_id, vector))
                    router_message_counts[router.id] += 1
        
        # Log per-router message counts
        for router_id, count in router_message_counts.items():
            if count > 0:
                self.log(f"Router {router_id} sending {count} messages to neighbors")
        
        self.log(f"Prepared {len(messages)} distance vector messages")
        
        # Process all messages at once
        for sender_id, receiver_id, vector in messages:
            self.log(f"Message: {sender_id} -> {receiver_id}")
            receiver_router = self.routers[receiver_id]
            changes = receiver_router.update_routing_table(sender_id, vector)
            messages_sent += 1
            
            if changes:
                any_changes = True
                self.log(f"Router {receiver_id} updated its routing table", "UPDATE")
            else:
                self.log(f"Router {receiver_id} made no changes")
        
        self.message_count += messages_sent
        
        # Print message summary
        print(f"\n--- Message Exchange Summary ---")
        for router_id, count in router_message_counts.items():
            if count > 0:
                total_sent = self.routers[router_id].messages_sent
                total_received = self.routers[router_id].messages_received
                print(f"Router {router_id}: Sent {count} messages this round ({total_sent} total), Received {total_received} total")
        
        self.log(f"=== DISTANCE VECTOR EXCHANGE ROUND END: {messages_sent} messages, changes={any_changes} ===")
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
            self.log("No edges available for cost update", "WARNING")
            return False
        
        # Pick a random edge
        router1_id, router2_id, old_cost = random.choice(edges)
        
        # Generate new cost (between 1 and 10)
        new_cost = random.randint(1, 200)
        while new_cost == old_cost:
            new_cost = random.randint(1, 200)
        
        self.log(f"=== LINK COST UPDATE: {router1_id} <-> {router2_id} from {old_cost} to {new_cost} ===", "UPDATE")
        
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
        
        # Print per-router message statistics
        print(f"\nPer-Router Message Statistics:")
        print("Router | Messages Sent | Messages Received | Total Messages")
        print("-" * 58)
        for router_id in sorted(self.routers.keys()):
            router = self.routers[router_id]
            total_msgs = router.messages_sent + router.messages_received
            print(f"{router_id:6} | {router.messages_sent:13} | {router.messages_received:17} | {total_msgs:13}")
        
        if self.verbose:
            print(f"\nCost update log:")
            for log_entry in self.logs:
                print(f"  {log_entry}")
    
    def check_convergence(self, max_iterations=5):
        """Check if network has converged by running updates without changes"""
        self.log(f"=== CONVERGENCE CHECK (max {max_iterations} iterations) ===")
        
        for i in range(max_iterations):
            self.log(f"Convergence check iteration {i+1}/{max_iterations}")
            messages_sent, changes = self.send_distance_vectors()
            
            if not changes:
                timestamp = time.time() - self.start_time
                self.log(f"CONVERGENCE ACHIEVED after {i+1} iterations", "SUCCESS")
                print(f"\n[Time = {timestamp:.1f}s] Network converged after {i+1} iterations. No more changes.")
                return True
            else:
                self.log(f"Changes still occurring in iteration {i+1}")
        
        self.log(f"Convergence not achieved within {max_iterations} iterations", "WARNING")
        return False
    
    def run_simulation(self, duration=120, update_interval=5, cost_update_interval=30):
        """Run the distance vector routing simulation without threads"""
        self.log("=== SIMULATION START ===")
        print("Starting Distance Vector Routing Simulation...")
        print(f"Simulation will run for {duration} seconds")
        print(f"Distance vector updates every {update_interval} seconds")
        print(f"Cost updates every {cost_update_interval} seconds")
        print(f"Verbose logging: {'ENABLED' if self.verbose else 'DISABLED'}")
        
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
                    self.log("Simulation duration reached")
                    break
                
                # Periodic distance vector updates
                if current_time - last_update_time >= update_interval:
                    self.iteration_count += 1
                    self.log(f"=== ITERATION {self.iteration_count} START (Time: {current_time:.1f}s) ===")
                    
                    messages_sent, changes_made = self.send_distance_vectors()
                    
                    if changes_made:
                        self.print_all_routing_tables(current_time, f"UPDATED ROUTING TABLES (Iteration {self.iteration_count})")
                        print(f"\n[Time = {current_time:.1f}s] Iteration {self.iteration_count}: {messages_sent} messages sent, tables updated")
                        self.log(f"Iteration {self.iteration_count}: Changes detected, checking convergence...")
                        
                        # Check for convergence after changes
                        self.check_convergence(3)
                    else:
                        print(f"\n[Time = {current_time:.1f}s] Iteration {self.iteration_count}: {messages_sent} messages sent, no changes (converged)")
                        self.log(f"Iteration {self.iteration_count}: No changes, network appears converged")
                    
                    last_update_time = current_time
                    self.log(f"=== ITERATION {self.iteration_count} END ===")
                
                # Periodic cost updates
                if current_time - last_cost_update_time >= cost_update_interval:
                    self.log(f"Time for cost update (interval: {cost_update_interval}s)")
                    cost_changed = self.update_random_link_cost()
                    
                    if cost_changed:
                        # Immediately send updates after cost change
                        self.log("Triggering immediate distance vector exchange after cost change")
                        messages_sent, changes_made = self.send_distance_vectors()
                        
                        if changes_made:
                            self.print_all_routing_tables(current_time, "ROUTING TABLES AFTER COST CHANGE")
                            print(f"\n[Time = {current_time:.1f}s] Cost change triggered {messages_sent} messages")
                            
                            # Show message activity after cost change
                            print("--- Message Activity After Cost Change ---")
                            for router_id in sorted(self.routers.keys()):
                                router = self.routers[router_id]
                                print(f"Router {router_id}: {router.messages_sent} sent, {router.messages_received} received")
                            
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
            self.log("Simulation interrupted by user", "WARNING")
            print("\nSimulation interrupted by user")
        
        # Final convergence check
        print(f"\n{'='*60}")
        print("FINAL CONVERGENCE CHECK")
        print(f"{'='*60}")
        self.log("=== FINAL CONVERGENCE CHECK ===")
        self.check_convergence(10)
        
        # Print final results
        timestamp = time.time() - self.start_time
        self.print_all_routing_tables(timestamp, "FINAL ROUTING TABLES")
        self.print_final_shortest_paths()
        self.print_statistics()
        
        self.log("=== SIMULATION END ===")
        print(f"\nSimulation completed!")


def main():
    """Main function to run the simulation"""
    print("Distance Vector Routing Algorithm Simulation")
    print("=" * 60)
    
    # Ask user for verbose logging preference
    verbose_input = input("Enable verbose logging? (y/N): ").strip().lower()
    verbose = verbose_input in ['y', 'yes', '1', 'true']
    
    # Ask user for log file preference
    log_file = None
    if verbose:
        log_file_input = input("Save logs to file? (y/N): ").strip().lower()
        if log_file_input in ['y', 'yes', '1', 'true']:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            log_file = f"dv_simulation_{timestamp}.log"
            print(f"Logs will be saved to: {log_file}")
        else:
            print("Logs will be displayed in console")
    
    if verbose:
        print("Verbose logging ENABLED - detailed algorithm steps will be shown")
    else:
        print("Verbose logging DISABLED - only major events will be shown")
    
    # Create and run simulation
    network = Network("topology.txt", verbose=verbose, log_file=log_file)
    
    # Run simulation: 90 seconds total, updates every 5s, cost changes every 30s
    network.run_simulation(duration=90, update_interval=5, cost_update_interval=30)
    
    # Final message about log file
    if log_file and verbose:
        print(f"\nDetailed logs have been saved to: {log_file}")


if __name__ == "__main__":
    main()