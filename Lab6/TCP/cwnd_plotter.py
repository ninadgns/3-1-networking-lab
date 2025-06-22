import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import sys
import os
from pathlib import Path

def load_cwnd_data(csv_file):
    """Load CWND data from CSV file"""
    try:
        df = pd.read_csv(csv_file)
        print(f"Successfully loaded {len(df)} data points from {csv_file}")
        return df
    except Exception as e:
        print(f"Error loading CSV file: {e}")
        return None

def create_cwnd_plot(df, save_plot=True, csv_filename=None):
    """Create enhanced CWND and SSThresh plot for TCP Reno"""
    
    # Create figure with high DPI for crisp lines
    fig, ax = plt.subplots(figsize=(16, 10))
    
    # Set professional styling
    ax.set_facecolor('#fafafa')
    fig.patch.set_facecolor('white')
    
    # Plot CWND with smooth line and subtle markers
    cwnd_line = ax.plot(df['Packet_Number'], df['CWND_MSS'], 
                       color='#2E86C1', linewidth=2.5, 
                       label='Congestion Window (CWND)', 
                       marker='o', markersize=2, markevery=5,
                       alpha=0.9, zorder=3)
    
    # Plot SSThresh with distinctive dashed line
    ssthresh_mss = df['SSThresh'] / 730  # Convert to MSS units
    ssthresh_line = ax.plot(df['Packet_Number'], ssthresh_mss, 
                           color='#E74C3C', linewidth=2.5, 
                           linestyle='--', label='Slow Start Threshold (SSThresh)',
                           alpha=0.9, zorder=2)
    
    # Enhanced timeout markers with better visibility
    timeout_points = df[df['Event'] == 'TIMEOUT']
    if not timeout_points.empty:
        timeout_scatter = ax.scatter(timeout_points['Packet_Number'], timeout_points['CWND_MSS'], 
                                   color='#C0392B', s=120, marker='X', 
                                   linewidth=2, label='Timeout Events', 
                                   zorder=5, edgecolors='white')
        
        # Add subtle vertical lines for timeout events
        for _, row in timeout_points.iterrows():
            ax.axvline(x=row['Packet_Number'], color='#C0392B', 
                      alpha=0.3, linestyle=':', linewidth=1.5, zorder=1)
    
    # Enhanced fast retransmit markers
    fast_retx_points = df[df['Event'] == 'FAST_RETRANSMIT']
    if not fast_retx_points.empty:
        fast_retx_scatter = ax.scatter(fast_retx_points['Packet_Number'], fast_retx_points['CWND_MSS'], 
                                     color='#F39C12', s=100, marker='^', 
                                     label='Fast Retransmit', zorder=4,
                                     edgecolors='white', linewidth=1)
    
    # Add congestion state background regions
    add_enhanced_state_regions(ax, df)
    
    # Professional styling
    ax.set_xlabel('Packet Sequence Number', fontsize=14, fontweight='600', color='#2C3E50')
    ax.set_ylabel('Window Size (MSS)', fontsize=14, fontweight='600', color='#2C3E50')
    ax.set_title('TCP Reno Congestion Control: CWND and SSThresh Evolution', 
                fontsize=18, fontweight='700', color='#2C3E50', pad=25)
    
    # Enhanced grid
    ax.grid(True, alpha=0.4, color='#BDC3C7', linestyle='-', linewidth=0.8)
    ax.set_axisbelow(True)
    
    # Professional legend with better positioning
    legend = ax.legend(fontsize=12, loc='upper left', framealpha=0.95, 
                      fancybox=True, shadow=True, borderpad=1,
                      edgecolor='#BDC3C7', facecolor='white')
    legend.get_frame().set_linewidth(1.2)
    
    # Set axis limits with appropriate padding
    x_padding = (df['Packet_Number'].max() - df['Packet_Number'].min()) * 0.02
    y_max = max(df['CWND_MSS'].max(), ssthresh_mss.max())
    
    ax.set_xlim(df['Packet_Number'].min() - x_padding, 
                df['Packet_Number'].max() + x_padding)
    ax.set_ylim(0, y_max * 1.15)
    
    # Add phase annotations
    add_phase_annotations(ax, df)
    
    # Remove top and right spines for cleaner look
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    ax.spines['left'].set_color('#BDC3C7')
    ax.spines['bottom'].set_color('#BDC3C7')
    
    # Style tick parameters
    ax.tick_params(axis='both', which='major', labelsize=11, 
                   colors='#2C3E50', width=1.2)
    
    plt.tight_layout()
    
    if save_plot:
        if csv_filename:
            plot_filename = f"tcp_reno_cwnd_{Path(csv_filename).stem}.png"
        else:
            plot_filename = "tcp_reno_cwnd.png"
        plt.savefig(plot_filename, dpi=300, bbox_inches='tight', 
                   facecolor='white', edgecolor='none')
        print(f"Enhanced plot saved as: {plot_filename}")
    
    return fig, ax

def add_enhanced_state_regions(ax, df):
    """Add enhanced congestion state background regions"""
    
    # Find state transitions
    state_changes = df[df['State'] != df['State'].shift()].copy()
    
    # Enhanced color mapping for states with better contrast
    state_colors = {
        'SLOW_START': '#E8F6F3',        # Very light teal
        'CONGESTION_AVOIDANCE': '#FDF2E9',  # Very light orange
        'FAST_RECOVERY': '#FADBD8'      # Very light red
    }
    
    # Add subtle background regions
    for i in range(len(state_changes)):
        start_packet = state_changes.iloc[i]['Packet_Number']
        state = state_changes.iloc[i]['State']
        
        if i < len(state_changes) - 1:
            end_packet = state_changes.iloc[i + 1]['Packet_Number']
        else:
            end_packet = df['Packet_Number'].max()
        
        if state in state_colors:
            ax.axvspan(start_packet, end_packet, 
                      alpha=0.3, color=state_colors[state], zorder=0)

def add_phase_annotations(ax, df):
    """Add TCP phase annotations at the top of the plot"""
    
    state_changes = df[df['State'] != df['State'].shift()].copy()
    
    if len(state_changes) == 0:
        return
    
    y_max = ax.get_ylim()[1]
    annotation_y = y_max * 0.92
    
    # State display names
    state_names = {
        'SLOW_START': 'Slow Start',
        'CONGESTION_AVOIDANCE': 'Congestion Avoidance', 
        'FAST_RECOVERY': 'Fast Recovery'
    }
    
    # Color coding for annotations
    state_annotation_colors = {
        'SLOW_START': '#16A085',
        'CONGESTION_AVOIDANCE': '#E67E22',
        'FAST_RECOVERY': '#E74C3C'
    }
    
    for i, (_, row) in enumerate(state_changes.iterrows()):
        if i < len(state_changes) - 1:
            next_packet = state_changes.iloc[i + 1]['Packet_Number']
            mid_point = (row['Packet_Number'] + next_packet) / 2
            width = next_packet - row['Packet_Number']
        else:
            mid_point = (row['Packet_Number'] + df['Packet_Number'].max()) / 2
            width = df['Packet_Number'].max() - row['Packet_Number']
        
        # Only add annotation if the region is wide enough
        if width > (df['Packet_Number'].max() - df['Packet_Number'].min()) * 0.08:
            state_display = state_names.get(row['State'], row['State'])
            color = state_annotation_colors.get(row['State'], '#34495E')
            
            ax.text(mid_point, annotation_y, state_display, 
                   ha='center', va='center', fontsize=11, fontweight='600',
                   color='white',
                   bbox=dict(boxstyle='round,pad=0.5', 
                           facecolor=color, alpha=0.8, edgecolor='none'))

def print_summary(df):
    """Print concise summary of TCP behavior"""
    
    print("\n" + "="*50)
    print("TCP RENO CONGESTION CONTROL SUMMARY")
    print("="*50)
    
    print(f"Total packets analyzed: {len(df)}")
    print(f"Maximum CWND reached: {df['CWND_MSS'].max()} MSS")
    print(f"Connection duration: {df['Time_ms'].max() - df['Time_ms'].min():.0f} ms")
    
    # Loss events
    timeouts = len(df[df['Event'] == 'TIMEOUT'])
    fast_retx = len(df[df['Event'] == 'FAST_RETRANSMIT'])
    
    print(f"\nLoss Recovery Events:")
    print(f"  • Timeout events: {timeouts}")
    print(f"  • Fast retransmit events: {fast_retx}")
    
    # State distribution
    print(f"\nCongestion Control Phases:")
    state_counts = df['State'].value_counts()
    total_packets = len(df)
    
    for state, count in state_counts.items():
        percentage = (count / total_packets) * 100
        state_display = state.replace('_', ' ').title()
        print(f"  • {state_display}: {percentage:.1f}% of connection")

def find_latest_csv():
    """Find the most recent CWND CSV file in current directory"""
    csv_files = list(Path('.').glob('cwnd_log_*.csv'))
    if csv_files:
        latest_file = max(csv_files, key=os.path.getctime)
        print(f"Found latest CSV file: {latest_file}")
        return str(latest_file)
    return None

def main():
    """Main function"""
    
    # Determine which CSV file to use
    if len(sys.argv) > 1:
        csv_file = sys.argv[1]
    else:
        csv_file = find_latest_csv()
        if not csv_file:
            print("No CSV file specified and no cwnd_log_*.csv files found.")
            print("Usage: python tcp_cwnd_plotter.py [csv_file]")
            return
    
    # Check if file exists
    if not os.path.exists(csv_file):
        print(f"Error: File '{csv_file}' not found!")
        return
    
    # Load and validate data
    df = load_cwnd_data(csv_file)
    if df is None:
        return
    
    # Create enhanced plot
    print("Creating enhanced TCP Reno CWND visualization...")
    fig, ax = create_cwnd_plot(df, csv_filename=csv_file)
    
    # Print concise summary
    print_summary(df)
    
    # Display plot
    print("\nDisplaying plot...")
    plt.show()

if __name__ == "__main__":
    main()