#!/bin/bash

# XDAG Node Block Comparison Script
# Dual-column side-by-side comparison

NODE1_API="http://127.0.0.1:10001/api/v1"
NODE2_API="http://127.0.0.1:10002/api/v1"
NODE1_NAME="Node1"
NODE2_NAME="Node2"

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;90m'
NC='\033[0m'
BOLD='\033[1m'

echo ""
echo -e "${BOLD}XDAG Node Comparison - Dual Column View${NC}"
echo ""

# Get block counts
curl -s "$NODE1_API/blocks/number" > "$TMPDIR/n1_count.json" &
curl -s "$NODE2_API/blocks/number" > "$TMPDIR/n2_count.json" &
wait

node1_height=$(python3 -c "import json; print(int(json.load(open('$TMPDIR/n1_count.json')).get('blockNumber', '0x0'), 16))" 2>/dev/null)
node2_height=$(python3 -c "import json; print(int(json.load(open('$TMPDIR/n2_count.json')).get('blockNumber', '0x0'), 16))" 2>/dev/null)

[ -z "$node1_height" ] || [ "$node1_height" -eq 0 ] && { echo -e "${RED}ERROR: Node1 unavailable${NC}"; exit 1; }
[ -z "$node2_height" ] || [ "$node2_height" -eq 0 ] && { echo -e "${RED}ERROR: Node2 unavailable${NC}"; exit 1; }

max_height=$(( node1_height < node2_height ? node1_height : node2_height ))

# Fetch all data
echo "Fetching data..."
pages=$(( (max_height + 99) / 100 ))
for page in $(seq 1 $pages); do
    curl -s "$NODE1_API/blocks?page=$page&size=100" > "$TMPDIR/n1_blocks_$page.json" &
    curl -s "$NODE2_API/blocks?page=$page&size=100" > "$TMPDIR/n2_blocks_$page.json" &
done
wait

python3 << PYTHON_SCRIPT
import json, os
tmpdir = "$TMPDIR"
max_height = $max_height

def load_blocks(prefix):
    blocks = {}
    page = 1
    while True:
        filepath = f"{tmpdir}/{prefix}_blocks_{page}.json"
        if not os.path.exists(filepath): break
        try:
            with open(filepath) as f:
                for b in json.load(f).get('data', []):
                    height = int(b.get('height', '0x0'), 16)
                    blocks[height] = b
        except: pass
        page += 1
    return blocks

n1_blocks, n2_blocks = load_blocks('n1'), load_blocks('n2')
epochs = set()
data = []
for h in range(1, max_height + 1):
    b1, b2 = n1_blocks.get(h, {}), n2_blocks.get(h, {})
    data.append({'height': h, 'n1': b1, 'n2': b2})
    for b in [b1, b2]:
        if b.get('epoch'): epochs.add(b.get('epoch'))

with open(f"{tmpdir}/epochs.txt", 'w') as f:
    for e in sorted(epochs): f.write(f"{e}\n")
with open(f"{tmpdir}/data.json", 'w') as f:
    json.dump(data, f)
with open(f"{tmpdir}/heights.txt", 'w') as f:
    for h in range(1, max_height + 1): f.write(f"{h}\n")
PYTHON_SCRIPT

batch=20
count=0
while read -r epoch; do
    curl -s "$NODE1_API/blocks/epoch/$epoch" > "$TMPDIR/n1_epoch_$epoch.json" &
    curl -s "$NODE2_API/blocks/epoch/$epoch" > "$TMPDIR/n2_epoch_$epoch.json" &
    count=$((count + 1)); [ $((count % batch)) -eq 0 ] && wait
done < "$TMPDIR/epochs.txt"
wait

count=0
while read -r height; do
    curl -s "$NODE1_API/blocks/$height" > "$TMPDIR/n1_block_$height.json" &
    curl -s "$NODE2_API/blocks/$height" > "$TMPDIR/n2_block_$height.json" &
    count=$((count + 1)); [ $((count % batch)) -eq 0 ] && wait
done < "$TMPDIR/heights.txt"
wait

# Display dual-column comparison
python3 << 'PYTHON_SCRIPT'
import json, os

tmpdir = os.environ.get('TMPDIR', '/tmp')

# Colors
R, G, Y, C, GR, NC, BD = '\033[0;31m', '\033[0;32m', '\033[1;33m', '\033[0;36m', '\033[0;90m', '\033[0m', '\033[1m'

with open(f"{tmpdir}/data.json") as f:
    data = json.load(f)

def load_json(path):
    try:
        with open(path) as f: return json.load(f)
    except: return {}

COL_WIDTH = 82

def format_block_line(block, detail, is_main=True):
    """Format a single block line with header (hash + coinbase) and body (links)"""
    if not block:
        return [f"{GR}(missing){NC}"]

    lines = []
    hash_str = block.get('hash', '-')
    state = block.get('state', '?')
    coinbase = block.get('coinbase', '-')

    # Header line: [State] hash | coinbase: xxx
    if is_main:
        if state == 'Main':
            lines.append(f"{G}[Main]{NC}   {hash_str} | coinbase: {coinbase}")
        else:
            lines.append(f"{Y}[{state}]{NC} {hash_str} | coinbase: {coinbase}")
    else:
        lines.append(f"{GR}[Orphan]{NC} {hash_str} | coinbase: {coinbase}")

    # Body: Links (only for main blocks with detail)
    if is_main and detail:
        links = detail.get('links', [])
        transactions = detail.get('transactions', [])

        # Separate block links and collect them
        block_links = []
        for link in links:
            link_type = link.get('type', 'unknown')
            link_hash = link.get('hash', '-')
            block_links.append({'type': link_type, 'hash': link_hash})

        # Sort block links by hash (smallest first)
        block_links.sort(key=lambda x: x['hash'])

        # Combine all items for display
        all_items = []
        for bl in block_links:
            all_items.append(('block', bl['type'], bl['hash']))
        for tx in transactions:
            tx_hash = tx.get('hash', '-') if isinstance(tx, dict) else str(tx)
            all_items.append(('tx', 'transaction', tx_hash))

        # Display with tree structure
        type_colors = {'parent': C, 'orphan': GR, 'transaction': G, 'unknown': NC}
        for i, (category, item_type, item_hash) in enumerate(all_items):
            is_last = (i == len(all_items) - 1)
            prefix = "└─" if is_last else "├─"
            tc = type_colors.get(item_type, NC)
            lines.append(f"          {prefix} {tc}[{item_type}]{NC} {item_hash}")

    return lines

def pad_line(line, width):
    """Pad line to fixed width, handling ANSI codes"""
    # Strip ANSI codes for length calculation
    import re
    visible = re.sub(r'\033\[[0-9;]*m', '', line)
    padding = width - len(visible)
    return line + ' ' * max(0, padding)

# Calculate totals
total_main = len(data)
identical_main = 0
total_orphans = 0
identical_orphans = 0
stats = {}

# Collect all data first
epochs_data = []
for item in data:
    h = item['height']
    b1, b2 = item['n1'], item['n2']

    h1 = b1.get('hash', '')
    h2 = b2.get('hash', '')
    epoch = b1.get('epoch') or b2.get('epoch') or 0

    main_match = h1 == h2 and h1
    if main_match: identical_main += 1

    d1 = load_json(f"{tmpdir}/n1_block_{h}.json")
    d2 = load_json(f"{tmpdir}/n2_block_{h}.json")

    # Load orphans
    n1_epoch = load_json(f"{tmpdir}/n1_epoch_{epoch}.json")
    n2_epoch = load_json(f"{tmpdir}/n2_epoch_{epoch}.json")

    orphans = {}
    for blk in n1_epoch.get('blocks', []):
        if blk.get('state') == 'Orphan':
            bh = blk.get('hash', '')
            if bh: orphans[bh] = {'n1': blk, 'n2': None}
    for blk in n2_epoch.get('blocks', []):
        if blk.get('state') == 'Orphan':
            bh = blk.get('hash', '')
            if bh:
                if bh in orphans: orphans[bh]['n2'] = blk
                else: orphans[bh] = {'n1': None, 'n2': blk}

    for oh, nodes in orphans.items():
        total_orphans += 1
        if nodes['n1'] and nodes['n2']:
            identical_orphans += 1

    # Track stats
    cb = b1.get('coinbase') or b2.get('coinbase')
    if cb:
        if cb not in stats: stats[cb] = {'main': 0, 'orphan': 0}
        if b1.get('state') == 'Main': stats[cb]['main'] += 1
    for oh, nodes in orphans.items():
        ocb = (nodes['n1'] or nodes['n2'] or {}).get('coinbase')
        if ocb:
            if ocb not in stats: stats[ocb] = {'main': 0, 'orphan': 0}
            stats[ocb]['orphan'] += 1

    epochs_data.append({
        'height': h, 'epoch': epoch,
        'b1': b1, 'b2': b2,
        'd1': d1, 'd2': d2,
        'main_match': main_match,
        'orphans': orphans
    })

# Print header
n1_count = len([d for d in epochs_data if d['b1'].get('hash')])
n2_count = len([d for d in epochs_data if d['b2'].get('hash')])

print(f"{BD}╔{'═'*COL_WIDTH}╦{'═'*COL_WIDTH}╗{NC}")
n1_title = f"{G} NODE 1 ({n1_count} blocks) {NC}".center(COL_WIDTH + 9)  # +9 for ANSI codes
n2_title = f"{G} NODE 2 ({n2_count} blocks) {NC}".center(COL_WIDTH + 9)
print(f"{BD}║{NC}{n1_title}{BD}║{NC}{n2_title}{BD}║{NC}")
print(f"{BD}╠{'═'*COL_WIDTH}╬{'═'*COL_WIDTH}╣{NC}")

# Print each epoch
for ed in epochs_data:
    h = ed['height']
    epoch = ed['epoch']
    b1, b2 = ed['b1'], ed['b2']
    d1, d2 = ed['d1'], ed['d2']
    main_match = ed['main_match']
    orphans = ed['orphans']

    # Header line for this height
    match_sym = f"{G}✓{NC}" if main_match else f"{R}✗{NC}"
    header = f"Height {h} │ Epoch {epoch}"
    left_header = pad_line(f" {BD}{header}{NC}", COL_WIDTH)
    right_header = pad_line(f" {BD}{header}{NC} {match_sym}", COL_WIDTH)
    print(f"{BD}║{NC}{left_header}{BD}║{NC}{right_header}{BD}║{NC}")

    # Main block lines
    left_lines = format_block_line(b1 if b1.get('hash') else None, d1, True)
    right_lines = format_block_line(b2 if b2.get('hash') else None, d2, True)

    # Pad to same length
    max_lines = max(len(left_lines), len(right_lines))
    while len(left_lines) < max_lines: left_lines.append("")
    while len(right_lines) < max_lines: right_lines.append("")

    for ll, rl in zip(left_lines, right_lines):
        left = pad_line(f" {ll}", COL_WIDTH)
        right = pad_line(f" {rl}", COL_WIDTH)
        print(f"{BD}║{NC}{left}{BD}║{NC}{right}{BD}║{NC}")

    # Orphan blocks
    for oh, nodes in sorted(orphans.items()):
        o1, o2 = nodes['n1'], nodes['n2']
        o_match = (o1 is not None) == (o2 is not None)

        left_orph = format_block_line(o1, None, False) if o1 else [f"  {GR}(missing){NC}"]
        right_orph = format_block_line(o2, None, False) if o2 else [f"  {GR}(missing){NC}"]

        for ll, rl in zip(left_orph, right_orph):
            o_sym = f"{G}✓{NC}" if o_match else f"{R}✗{NC}"
            left = pad_line(f" {ll}", COL_WIDTH)
            right = pad_line(f" {rl} {o_sym}", COL_WIDTH)
            print(f"{BD}║{NC}{left}{BD}║{NC}{right}{BD}║{NC}")

    print(f"{BD}╠{'─'*COL_WIDTH}╬{'─'*COL_WIDTH}╣{NC}")

# Summary
print(f"{BD}╠{'═'*COL_WIDTH}╬{'═'*COL_WIDTH}╣{NC}")
summary_left = f" Main: {identical_main}/{total_main} match"
summary_right = f" Orphan: {identical_orphans}/{total_orphans} match"

if identical_main == total_main and identical_orphans == total_orphans:
    result = f"{G}✓ ALL IDENTICAL{NC}"
else:
    result = f"{R}✗ DIFFERENCES FOUND{NC}"

print(f"{BD}║{NC}{pad_line(summary_left, COL_WIDTH)}{BD}║{NC}{pad_line(summary_right, COL_WIDTH)}{BD}║{NC}")
print(f"{BD}║{NC}{pad_line(f' {result}', COL_WIDTH)}{BD}║{NC}{pad_line('', COL_WIDTH)}{BD}║{NC}")
print(f"{BD}╚{'═'*COL_WIDTH}╩{'═'*COL_WIDTH}╝{NC}")

# Stats
print("")
print(f"{BD}Block Production:{NC}")
total_all_main = sum(s['main'] for s in stats.values())
for cb, s in sorted(stats.items(), key=lambda x: x[1]['main'], reverse=True):
    main, orphan = s['main'], s['orphan']
    # Win Rate: percentage of all main blocks won by this coinbase
    win_rate = (main / total_all_main * 100) if total_all_main > 0 else 0
    # Efficiency: percentage of this coinbase's blocks that became main
    total_produced = main + orphan
    efficiency = (main / total_produced * 100) if total_produced > 0 else 0
    win_c = G if win_rate >= 40 else Y if win_rate >= 20 else GR
    eff_c = G if efficiency >= 50 else Y if efficiency >= 30 else R
    print(f"  {cb}:")
    print(f"      {win_c}Win Rate: {win_rate:5.1f}%{NC} ({main}/{total_all_main} main blocks)")
    print(f"      {eff_c}Efficiency: {efficiency:5.1f}%{NC} ({main} main / {total_produced} produced, {orphan} orphan)")
print("")
PYTHON_SCRIPT
