#!/bin/bash

# XDAG Node Block Comparison Script (Optimized)
# Compares main blocks between two XDAG nodes via HTTP API
# Uses batch queries and parallel execution for performance

# Node configurations
NODE1_API="http://127.0.0.1:10001/api/v1"
NODE2_API="http://127.0.0.1:10002/api/v1"
NODE1_NAME="Node1"
NODE2_NAME="Node2"

# Temp directory for parallel results
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo ""
echo "+====================================================================================================+"
echo "|                              XDAG Node Block Comparison Tool                                       |"
echo "+====================================================================================================+"
echo ""

# Step 1: Get block counts from both nodes (parallel)
echo "Querying nodes..."
curl -s "$NODE1_API/blocks/number" > "$TMPDIR/n1_count.json" &
curl -s "$NODE2_API/blocks/number" > "$TMPDIR/n2_count.json" &
wait

node1_height=$(python3 -c "import json; print(int(json.load(open('$TMPDIR/n1_count.json')).get('blockNumber', '0x0'), 16))" 2>/dev/null)
node2_height=$(python3 -c "import json; print(int(json.load(open('$TMPDIR/n2_count.json')).get('blockNumber', '0x0'), 16))" 2>/dev/null)

if [ -z "$node1_height" ] || [ "$node1_height" -eq 0 ]; then
    echo -e "${RED}ERROR: Failed to query Node1${NC}"
    exit 1
fi
if [ -z "$node2_height" ] || [ "$node2_height" -eq 0 ]; then
    echo -e "${RED}ERROR: Failed to query Node2${NC}"
    exit 1
fi

echo ""
echo "Node Status:"
echo "+------------------------------------------+"
printf "| %-20s: %8s blocks   |\n" "$NODE1_NAME (Port 10001)" "$node1_height"
printf "| %-20s: %8s blocks   |\n" "$NODE2_NAME (Port 10002)" "$node2_height"
echo "+------------------------------------------+"
echo ""

max_height=$(( node1_height < node2_height ? node1_height : node2_height ))

# Step 2: Batch fetch all blocks from both nodes (parallel)
echo "Fetching all blocks in batch..."

# Calculate pages needed (100 blocks per page)
pages=$(( (max_height + 99) / 100 ))

# Fetch all pages in parallel
for page in $(seq 1 $pages); do
    curl -s "$NODE1_API/blocks?page=$page&size=100" > "$TMPDIR/n1_blocks_$page.json" &
    curl -s "$NODE2_API/blocks?page=$page&size=100" > "$TMPDIR/n2_blocks_$page.json" &
done
wait

# Step 3: Parse and compare using Python (much faster than bash loops)
echo "Comparing blocks (1 to $max_height)..."
echo ""

export TMPDIR MAX_HEIGHT="$max_height"

python3 << PYTHON_SCRIPT
import json
import os
import sys
from collections import defaultdict

tmpdir = "$TMPDIR"
max_height = $max_height

# Color codes
RED = '\033[0;31m'
GREEN = '\033[0;32m'
YELLOW = '\033[1;33m'
CYAN = '\033[0;36m'
NC = '\033[0m'

# Load all blocks from both nodes
def load_blocks(prefix):
    blocks = {}
    page = 1
    while True:
        filepath = f"{tmpdir}/{prefix}_blocks_{page}.json"
        if not os.path.exists(filepath):
            break
        try:
            with open(filepath) as f:
                data = json.load(f)
                for b in data.get('data', []):
                    height = int(b.get('height', '0x0'), 16)
                    blocks[height] = b
        except:
            pass
        page += 1
    return blocks

n1_blocks = load_blocks('n1')
n2_blocks = load_blocks('n2')

# Collect epochs that need detailed view
epochs_to_check = set()
for h in range(1, max_height + 1):
    b1 = n1_blocks.get(h, {})
    b2 = n2_blocks.get(h, {})
    if b1.get('epoch'):
        epochs_to_check.add(b1.get('epoch'))
    if b2.get('epoch'):
        epochs_to_check.add(b2.get('epoch'))

# Write epochs to file for parallel fetching
with open(f"{tmpdir}/epochs_to_fetch.txt", 'w') as f:
    for epoch in epochs_to_check:
        f.write(f"{epoch}\n")

# Write block comparison data for later use
comparison_data = []
for h in range(1, max_height + 1):
    b1 = n1_blocks.get(h, {})
    b2 = n2_blocks.get(h, {})
    comparison_data.append({
        'height': h,
        'n1': b1,
        'n2': b2
    })

with open(f"{tmpdir}/comparison_data.json", 'w') as f:
    json.dump(comparison_data, f)

print(f"Found {len(epochs_to_check)} unique epochs to check")
PYTHON_SCRIPT

# Step 4: Fetch epoch data in parallel (batch of 20 concurrent requests)
echo "Fetching epoch details..."
epoch_count=0
batch_size=20

while read -r epoch; do
    curl -s "$NODE1_API/blocks/epoch/$epoch" > "$TMPDIR/n1_epoch_$epoch.json" &
    curl -s "$NODE2_API/blocks/epoch/$epoch" > "$TMPDIR/n2_epoch_$epoch.json" &
    epoch_count=$((epoch_count + 1))

    # Wait every batch_size requests to avoid too many concurrent connections
    if [ $((epoch_count % batch_size)) -eq 0 ]; then
        wait
    fi
done < "$TMPDIR/epochs_to_fetch.txt"
wait

# Step 5: Final comparison and output
python3 << PYTHON_SCRIPT
import json
import os

tmpdir = "$TMPDIR"

# Color codes
RED = '\033[0;31m'
GREEN = '\033[0;32m'
YELLOW = '\033[1;33m'
CYAN = '\033[0;36m'
NC = '\033[0m'

# Load comparison data
with open(f"{tmpdir}/comparison_data.json") as f:
    comparison_data = json.load(f)

# Load epoch data
def load_epoch(prefix, epoch):
    try:
        with open(f"{tmpdir}/{prefix}_epoch_{epoch}.json") as f:
            return json.load(f)
    except:
        return {}

# Print table
def print_separator():
    print("+--------+----------+---------------------------+---------------------------+--------+----------------------+--------+")

def print_header():
    print_separator()
    print(f"| {'Height':<6} | {'Epoch':<8} | {'Node1 Hash':<25} | {'Node2 Hash':<25} | {'Diff':<6} | {'Coinbase':<20} | {'Match':<6} |")
    print_separator()

# Statistics
total = 0
identical = 0
different = 0
missing = 0
epoch_match = 0
epoch_mismatch = 0

# Track block production by coinbase
coinbase_stats = {}

print_header()

for item in comparison_data:
    h = item['height']
    b1 = item['n1']
    b2 = item['n2']
    total += 1

    n1_hash = b1.get('hash', '')
    n2_hash = b2.get('hash', '')

    if not n1_hash or not n2_hash:
        missing += 1
        print(f"| {h:<6} | {'N/A':<8} | {'MISSING':<25} | {'MISSING':<25} | {'N/A':<6} | {'N/A':<20} | {RED}{'MISS':<6}{NC} |")
        continue

    epoch = b1.get('epoch', 0)
    diff = int(b1.get('difficulty', '0'), 16) if isinstance(b1.get('difficulty'), str) else b1.get('difficulty', 0)
    coinbase = b1.get('coinbase', 'N/A')

    # Track main block production by coinbase
    if coinbase not in coinbase_stats:
        coinbase_stats[coinbase] = {'main': 0, 'orphan': 0}
    coinbase_stats[coinbase]['main'] += 1

    n1_short = n1_hash[:14] + '...' + n1_hash[-8:] if len(n1_hash) > 22 else n1_hash
    n2_short = n2_hash[:14] + '...' + n2_hash[-8:] if len(n2_hash) > 22 else n2_hash
    cb_short = coinbase[:8] + '...' + coinbase[-4:] if len(coinbase) > 12 else coinbase

    # Compare
    match = (n1_hash == n2_hash and
             b1.get('epoch') == b2.get('epoch') and
             b1.get('difficulty') == b2.get('difficulty') and
             b1.get('state') == b2.get('state'))

    if match:
        identical += 1
        status = f"{GREEN}{'OK':<6}{NC}"
    else:
        different += 1
        status = f"{RED}{'X':<6}{NC}"

    print(f"| {h:<6} | {epoch:<8} | {n1_short:<25} | {n2_short:<25} | {diff:<6} | {cb_short:<20} | {status} |")

    # Check epoch blocks
    n1_epoch = load_epoch('n1', epoch)
    n2_epoch = load_epoch('n2', epoch)
    n1_count = n1_epoch.get('blockCount', 0)
    n2_count = n2_epoch.get('blockCount', 0)

    if n1_count > 1 or n2_count > 1 or n1_count != n2_count:
        if n1_count == n2_count:
            epoch_match += 1
        else:
            epoch_mismatch += 1

        # Format orphan blocks and track stats
        def format_orphan_blocks(data):
            blocks = data.get('blocks', [])
            orphans = [b for b in blocks if b.get('state') != 'Main']
            if not orphans:
                return "(no orphans)", []
            parts = []
            orphan_coinbases = []
            for b in orphans:
                h = b.get('hash', '')[:16] + '...'
                cb = b.get('coinbase', '')
                cb_short = cb[:6] + '..' + cb[-3:] if len(cb) > 9 else cb
                parts.append(f"{h}({cb_short})")
                orphan_coinbases.append(cb)
            return '  '.join(parts), orphan_coinbases

        n1_orphans, n1_orphan_cbs = format_orphan_blocks(n1_epoch)
        n2_orphans, n2_orphan_cbs = format_orphan_blocks(n2_epoch)

        # Track orphan stats (use n1 since both should be same when matching)
        for cb in n1_orphan_cbs:
            if cb not in coinbase_stats:
                coinbase_stats[cb] = {'main': 0, 'orphan': 0}
            coinbase_stats[cb]['orphan'] += 1

        # Check if both nodes have same orphan view
        if n1_count == n2_count and n1_orphans == n2_orphans:
            # Same view - show once
            print(f"|        |          | {CYAN}Orphans ({n1_count - 1}):{NC} {YELLOW}{n1_orphans}{NC}")
        else:
            # Different views - show both
            print(f"|        |          | {CYAN}N1 orphans:{NC} {YELLOW}{n1_orphans}{NC}")
            print(f"|        |          | {CYAN}N2 orphans:{NC} {YELLOW}{n2_orphans}{NC} {RED}!{NC}")
    else:
        epoch_match += 1

print_separator()

# Summary
print()
print("+==============================================================================+")
print("|                           Comparison Summary                                 |")
print("+==============================================================================+")
print(f"| Total blocks compared:    {total:<51}|")
print(f"| {GREEN}Identical blocks:         {identical:<51}{NC}|")
print(f"| {RED}Different blocks:         {different:<51}{NC}|")
print(f"| {YELLOW}Missing blocks:           {missing:<51}{NC}|")
print("+------------------------------------------------------------------------------+")
print(f"| {GREEN}Matching epoch views:     {epoch_match:<51}{NC}|")
print(f"| {RED}Mismatched epoch views:   {epoch_mismatch:<51}{NC}|")
print("+==============================================================================+")

# Block production statistics by coinbase
if coinbase_stats and total > 0:
    print()
    print("+==============================================================================+")
    print("|                        Block Production Statistics                           |")
    print("+==============================================================================+")
    print(f"| {'Coinbase':<34} | {'Main':<8} | {'Orphan':<8} | {'Win Rate':<10} |")
    print("+------------------------------------+----------+----------+------------+")

    # Sort by main block count descending
    sorted_stats = sorted(coinbase_stats.items(), key=lambda x: x[1]['main'], reverse=True)

    for cb, stats in sorted_stats:
        main_count = stats['main']
        orphan_count = stats['orphan']
        total_produced = main_count + orphan_count
        win_rate = (main_count / total_produced * 100) if total_produced > 0 else 0

        cb_short = cb[:12] + '...' + cb[-8:] if len(cb) > 20 else cb
        win_rate_str = f"{win_rate:.1f}%"

        # Color based on win rate
        if win_rate >= 50:
            color = GREEN
        elif win_rate >= 30:
            color = YELLOW
        else:
            color = RED

        print(f"| {cb_short:<34} | {main_count:<8} | {orphan_count:<8} | {color}{win_rate_str:<10}{NC} |")

    print("+==============================================================================+")

print()

if different == 0 and missing == 0 and epoch_mismatch == 0:
    print(f"{GREEN}SUCCESS: All blocks and epoch views are identical!{NC}")
    print("  The two nodes are perfectly synchronized.")
else:
    print(f"{RED}FAILURE: Nodes are not fully synchronized!{NC}")
    if different > 0:
        print(f"  - {different} main chain block(s) differ")
    if missing > 0:
        print(f"  - {missing} block(s) missing")
    if epoch_mismatch > 0:
        print(f"  - {epoch_mismatch} epoch(s) have different block counts")
print()
PYTHON_SCRIPT
