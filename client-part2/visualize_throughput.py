#!/usr/bin/env python3
"""
Visualization script for Part 3
Creates a simple line chart showing throughput over time
"""

import pandas as pd
import matplotlib.pyplot as plt

def create_throughput_chart():
  # Load data
  df = pd.read_csv('throughput.csv')

  # Create figure
  plt.figure(figsize=(12, 6))

  # Plot
  plt.plot(df['time_seconds'], df['messages_per_10_seconds'],
           linewidth=2, color='#2E86AB', marker='o', markersize=4)

  # Labels and title
  plt.xlabel('Time (seconds)', fontsize=12, fontweight='bold')
  plt.ylabel('Messages per 10 seconds', fontsize=12, fontweight='bold')
  plt.title('Throughput Over Time - 500,000 Messages Load Test',
            fontsize=14, fontweight='bold')

  # Grid
  plt.grid(True, alpha=0.3, linestyle='--')

  # Add average line
  avg_throughput = df['messages_per_10_seconds'].mean()
  plt.axhline(y=avg_throughput, color='red', linestyle='--',
              label=f'Average: {avg_throughput:.0f} msg/10s')

  plt.legend()
  plt.tight_layout()

  # Save
  plt.savefig('throughput-chart.png', dpi=300, bbox_inches='tight')
  print("✓ Chart saved as throughput-chart.png")

  plt.close()

if __name__ == "__main__":
  try:
    create_throughput_chart()
    print("✅ Visualization complete!")
  except FileNotFoundError:
    print("❌ Error: throughput.csv not found!")
    print("   Run the load test client first to generate CSV files.")
  except Exception as e:
    print(f"❌ Error creating chart: {e}")