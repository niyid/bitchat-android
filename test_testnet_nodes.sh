#!/bin/bash

echo "🔍 Testing Monero Stagenet Nodes (More Reliable Than Testnet)"
echo "=========================================================="

# Stagenet nodes (port 48080 = 28080 + 20000)
STAGENET_NODES=(
    "node.xmr.to:48080"
    "node.moneroworld.com:48080"
    "stagenet.community.rino.io:48080"
    "stagenet.xmr.ditatompel.com:48080"
    "xmr-lux.boldsuck.org:48080"
)

working_nodes=0
working_list=()

echo "Stagenet is more stable than testnet for development..."
echo ""

for node in "${STAGENET_NODES[@]}"; do
    IFS=':' read -r host port <<< "$node"
    echo -n "Testing $node ... "
    
    if timeout 5 nc -zv "$host" "$port" &>/dev/null; then
        echo -n "port open ... "
        
        response=$(timeout 10 curl -s -X POST "http://$host:$port/get_info" \
                  -H "Content-Type: application/json" -d '{}' 2>/dev/null)
        
        if echo "$response" | grep -q '"stagenet".*true' || 
           (echo "$response" | grep -q '"height"' && echo "$response" | grep -q '"status"'); then
            echo "✅ STAGENET WORKING"
            ((working_nodes++))
            working_list+=("$node")
        else
            echo "❌ Not responding"
        fi
    else
        echo "❌ Port closed"
    fi
done

echo ""
echo "📊 Stagenet Results: $working_nodes/${#STAGENET_NODES[@]} nodes working"

if [ ${#working_list[@]} -gt 0 ]; then
    echo ""
    echo "✅ Working stagenet nodes:"
    for node in "${working_list[@]}"; do
        echo "  - $node"
    done
    
    echo ""
    echo "📝 For stagenet in Java (networkType = 2):"
    echo "private static final String[] STAGENET_BACKUP_NODES = {"
    for i in "${!working_list[@]}"; do
        if [ $i -eq $((${#working_list[@]} - 1)) ]; then
            echo "    \"${working_list[i]}\""
        else
            echo "    \"${working_list[i]}\","
        fi
    done
    echo "};"
    echo ""
    echo "💡 Use networkType = 2 in initializeWallet() for stagenet"
fi
