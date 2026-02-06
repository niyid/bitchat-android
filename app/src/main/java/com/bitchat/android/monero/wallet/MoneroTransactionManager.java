package com.bitchat.android.monero.wallet;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/**
 * Monero Transaction Search and Management with Multi-Node Support
 * 
 * IMPORTANT: On stagenet (or any network), transactions may be temporarily
 * inconsistent across nodes due to propagation delays. This implementation
 * allows querying multiple nodes to find transactions that may not have
 * propagated to all nodes yet.
 */
public class MoneroTransactionManager {
    
    private List<NodeConnection> nodes;
    private int defaultNodeIndex = 0;
    
    public static class NodeConnection {
        public String host;
        public int port;
        public String username;
        public String password;
        public String rpcUrl;
        
        public NodeConnection(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.rpcUrl = "http://" + host + ":" + port + "/json_rpc";
        }
    }
    
    // Constructor with single node
    public MoneroTransactionManager(String host, int port, String username, String password) {
        this.nodes = new ArrayList<>();
        this.nodes.add(new NodeConnection(host, port, username, password));
    }
    
    // Constructor with multiple nodes
    public MoneroTransactionManager(List<NodeConnection> nodes) {
        this.nodes = nodes;
    }
    
    // Add a node to the list
    public void addNode(String host, int port, String username, String password) {
        this.nodes.add(new NodeConnection(host, port, username, password));
    }
    
    // Set which node to use by default
    public void setDefaultNode(int index) {
        if (index >= 0 && index < nodes.size()) {
            this.defaultNodeIndex = index;
        }
    }
    
    /**
     * Search for a transaction across ALL connected nodes
     * Useful when transaction might not have propagated to all nodes yet
     */
    public Transaction searchTransactionAcrossNodes(String txid) throws Exception {
        List<Future<Transaction>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(nodes.size());
        
        // Query all nodes in parallel
        for (int i = 0; i < nodes.size(); i++) {
            final int nodeIndex = i;
            futures.add(executor.submit(() -> {
                try {
                    return getTransactionByTxId(txid, nodeIndex);
                } catch (Exception e) {
                    System.err.println("Node " + nodeIndex + " failed: " + e.getMessage());
                    return null;
                }
            }));
        }
        
        // Wait for first successful result
        for (Future<Transaction> future : futures) {
            try {
                Transaction tx = future.get(10, TimeUnit.SECONDS);
                if (tx != null) {
                    executor.shutdownNow();
                    return tx;
                }
            } catch (Exception e) {
                // Continue to next node
            }
        }
        
        executor.shutdown();
        return null; // Transaction not found on any node
    }
    
    /**
     * Get transaction from a specific node
     */
    public Transaction getTransactionByTxId(String txid, int nodeIndex) throws Exception {
        JSONObject params = new JSONObject();
        params.put("txid", txid);
        
        JSONObject result = sendRPCRequest("get_transfer_by_txid", params, nodeIndex);
        
        if (result.has("transfer")) {
            Transaction tx = parseTransaction(result.getJSONObject("transfer"), "unknown");
            tx.nodeSource = nodeIndex; // Track which node returned this
            return tx;
        }
        
        return null;
    }
    
    /**
     * Get transaction from default node
     */
    public Transaction getTransactionByTxId(String txid) throws Exception {
        return getTransactionByTxId(txid, defaultNodeIndex);
    }
    
    /**
     * Check transaction pool (mempool) across all nodes
     * This is useful for finding unconfirmed transactions that are propagating
     */
    public Map<Integer, List<String>> checkMempoolAcrossNodes() throws Exception {
        Map<Integer, List<String>> mempoolByNode = new HashMap<>();
        
        for (int i = 0; i < nodes.size(); i++) {
            try {
                JSONObject result = sendRPCRequest("get_transaction_pool", new JSONObject(), i);
                List<String> txids = new ArrayList<>();
                
                if (result.has("transactions")) {
                    JSONArray txs = result.getJSONArray("transactions");
                    for (int j = 0; j < txs.length(); j++) {
                        txids.add(txs.getJSONObject(j).getString("id_hash"));
                    }
                }
                
                mempoolByNode.put(i, txids);
            } catch (Exception e) {
                System.err.println("Node " + i + " mempool check failed: " + e.getMessage());
            }
        }
        
        return mempoolByNode;
    }
    
    /**
     * Get all transfers from a specific node
     */
    public List<Transaction> getAllTransactions(boolean incoming, boolean outgoing, 
                                                boolean pending, boolean failed, 
                                                boolean pool, int nodeIndex) throws Exception {
        JSONObject params = new JSONObject();
        params.put("in", incoming);
        params.put("out", outgoing);
        params.put("pending", pending);
        params.put("failed", failed);
        params.put("pool", pool);
        
        JSONObject result = sendRPCRequest("get_transfers", params, nodeIndex);
        
        List<Transaction> transactions = new ArrayList<>();
        
        // Parse incoming transactions
        if (result.has("in")) {
            JSONArray inArray = result.getJSONArray("in");
            for (int i = 0; i < inArray.length(); i++) {
                Transaction tx = parseTransaction(inArray.getJSONObject(i), "in");
                tx.nodeSource = nodeIndex;
                transactions.add(tx);
            }
        }
        
        // Parse outgoing transactions
        if (result.has("out")) {
            JSONArray outArray = result.getJSONArray("out");
            for (int i = 0; i < outArray.length(); i++) {
                Transaction tx = parseTransaction(outArray.getJSONObject(i), "out");
                tx.nodeSource = nodeIndex;
                transactions.add(tx);
            }
        }
        
        // Parse pending transactions
        if (result.has("pending")) {
            JSONArray pendingArray = result.getJSONArray("pending");
            for (int i = 0; i < pendingArray.length(); i++) {
                Transaction tx = parseTransaction(pendingArray.getJSONObject(i), "pending");
                tx.nodeSource = nodeIndex;
                transactions.add(tx);
            }
        }
        
        return transactions;
    }
    
    /**
     * Get all transactions from default node
     */
    public List<Transaction> getAllTransactions(boolean incoming, boolean outgoing, 
                                                boolean pending, boolean failed, 
                                                boolean pool) throws Exception {
        return getAllTransactions(incoming, outgoing, pending, failed, pool, defaultNodeIndex);
    }
    
    /**
     * Aggregate transactions from all nodes (removes duplicates)
     * Useful when you want a complete view across the network
     */
    public List<Transaction> aggregateTransactionsFromAllNodes() throws Exception {
        Map<String, Transaction> uniqueTxs = new HashMap<>();
        
        for (int i = 0; i < nodes.size(); i++) {
            try {
                List<Transaction> txs = getAllTransactions(true, true, true, false, true, i);
                for (Transaction tx : txs) {
                    // Keep the transaction with highest confirmations if duplicate
                    if (!uniqueTxs.containsKey(tx.txid) || 
                        tx.confirmations > uniqueTxs.get(tx.txid).confirmations) {
                        uniqueTxs.put(tx.txid, tx);
                    }
                }
            } catch (Exception e) {
                System.err.println("Node " + i + " aggregation failed: " + e.getMessage());
            }
        }
        
        return new ArrayList<>(uniqueTxs.values());
    }
    
    /**
     * Search transactions by various criteria (local filtering)
     */
    public List<Transaction> searchTransactions(String searchTerm, SearchCriteria criteria) throws Exception {
        return searchTransactions(searchTerm, criteria, defaultNodeIndex);
    }
    
    /**
     * Search transactions on a specific node
     */
    public List<Transaction> searchTransactions(String searchTerm, SearchCriteria criteria, 
                                                int nodeIndex) throws Exception {
        List<Transaction> allTransactions = getAllTransactions(true, true, true, false, true, nodeIndex);
        List<Transaction> filtered = new ArrayList<>();
        
        for (Transaction tx : allTransactions) {
            boolean matches = false;
            
            switch (criteria) {
                case TRANSACTION_ID:
                    matches = tx.txid.toLowerCase().contains(searchTerm.toLowerCase());
                    break;
                case ADDRESS:
                    matches = tx.address.toLowerCase().contains(searchTerm.toLowerCase());
                    break;
                case PAYMENT_ID:
                    matches = tx.paymentId.toLowerCase().contains(searchTerm.toLowerCase());
                    break;
                case AMOUNT:
                    matches = String.valueOf(tx.amount).contains(searchTerm);
                    break;
                case BLOCK_HEIGHT:
                    matches = String.valueOf(tx.height).contains(searchTerm);
                    break;
                case NOTE:
                    matches = tx.note.toLowerCase().contains(searchTerm.toLowerCase());
                    break;
                case ALL:
                    matches = tx.txid.toLowerCase().contains(searchTerm.toLowerCase()) ||
                             tx.address.toLowerCase().contains(searchTerm.toLowerCase()) ||
                             tx.paymentId.toLowerCase().contains(searchTerm.toLowerCase()) ||
                             tx.note.toLowerCase().contains(searchTerm.toLowerCase()) ||
                             String.valueOf(tx.amount).contains(searchTerm) ||
                             String.valueOf(tx.height).contains(searchTerm);
                    break;
            }
            
            if (matches) {
                filtered.add(tx);
            }
        }
        
        return filtered;
    }
    
    /**
     * Rescan the blockchain for missing transactions
     */
    public void rescanBlockchain() throws Exception {
        rescanBlockchain(defaultNodeIndex);
    }
    
    public void rescanBlockchain(int nodeIndex) throws Exception {
        sendRPCRequest("rescan_blockchain", new JSONObject(), nodeIndex);
    }
    
    /**
     * Refresh wallet to get latest transactions
     */
    public RefreshResult refreshWallet(long startHeight) throws Exception {
        return refreshWallet(startHeight, defaultNodeIndex);
    }
    
    public RefreshResult refreshWallet(long startHeight, int nodeIndex) throws Exception {
        JSONObject params = new JSONObject();
        if (startHeight > 0) {
            params.put("start_height", startHeight);
        }
        
        JSONObject result = sendRPCRequest("refresh", params, nodeIndex);
        
        RefreshResult refresh = new RefreshResult();
        refresh.blocksFetched = result.getInt("blocks_fetched");
        refresh.receivedMoney = result.getBoolean("received_money");
        refresh.nodeIndex = nodeIndex;
        
        return refresh;
    }
    
    /**
     * Get node sync status
     */
    public NodeSyncStatus getNodeStatus(int nodeIndex) throws Exception {
        JSONObject result = sendRPCRequest("get_height", new JSONObject(), nodeIndex);
        
        NodeSyncStatus status = new NodeSyncStatus();
        status.height = result.getLong("height");
        status.nodeIndex = nodeIndex;
        
        return status;
    }
    
    /**
     * Check which nodes are in sync with each other
     */
    public Map<Integer, NodeSyncStatus> checkNodesSyncStatus() throws Exception {
        Map<Integer, NodeSyncStatus> statuses = new HashMap<>();
        
        for (int i = 0; i < nodes.size(); i++) {
            try {
                statuses.put(i, getNodeStatus(i));
            } catch (Exception e) {
                System.err.println("Node " + i + " status check failed: " + e.getMessage());
            }
        }
        
        return statuses;
    }
    
    // Helper method to send RPC requests to a specific node
    private JSONObject sendRPCRequest(String method, JSONObject params, int nodeIndex) throws Exception {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            throw new IllegalArgumentException("Invalid node index: " + nodeIndex);
        }
        
        NodeConnection node = nodes.get(nodeIndex);
        
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", "0");
        request.put("method", method);
        request.put("params", params);
        
        URL url = new URL(node.rpcUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000); // 10 second timeout
        conn.setReadTimeout(30000); // 30 second timeout
        
        // Add authentication if provided
        if (node.username != null && node.password != null) {
            String auth = node.username + ":" + node.password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
        }
        
        // Send request
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = request.toString().getBytes("utf-8");
            os.write(input, 0, input.length);
        }
        
        // Read response
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP error code: " + responseCode + " from node " + nodeIndex);
        }
        
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            
            JSONObject jsonResponse = new JSONObject(response.toString());
            
            if (jsonResponse.has("error")) {
                throw new Exception("RPC Error from node " + nodeIndex + ": " + 
                                  jsonResponse.getJSONObject("error").getString("message"));
            }
            
            return jsonResponse.getJSONObject("result");
        }
    }
    
    // Helper method to parse transaction
    private Transaction parseTransaction(JSONObject json, String type) {
        Transaction tx = new Transaction();
        tx.txid = json.optString("txid", "");
        tx.address = json.optString("address", "");
        tx.paymentId = json.optString("payment_id", "");
        tx.amount = json.optLong("amount", 0);
        tx.fee = json.optLong("fee", 0);
        tx.height = json.optLong("height", 0);
        tx.timestamp = json.optLong("timestamp", 0);
        tx.confirmations = json.optInt("confirmations", 0);
        tx.type = type;
        tx.note = json.optString("note", "");
        tx.unlockTime = json.optLong("unlock_time", 0);
        tx.locked = json.optBoolean("locked", false);
        
        return tx;
    }
    
    // Data classes
    public static class Transaction {
        public String txid;
        public String address;
        public String paymentId;
        public long amount;
        public long fee;
        public long height;
        public long timestamp;
        public int confirmations;
        public String type;
        public String note;
        public long unlockTime;
        public boolean locked;
        public int nodeSource = -1; // Which node returned this transaction
        
        @Override
        public String toString() {
            return String.format("TX: %s, Amount: %d, Type: %s, Height: %d, Confirmations: %d, Node: %d", 
                                txid, amount, type, height, confirmations, nodeSource);
        }
    }
    
    public static class Balance {
        public long balance;
        public long unlockedBalance;
    }
    
    public static class RefreshResult {
        public int blocksFetched;
        public boolean receivedMoney;
        public int nodeIndex;
    }
    
    public static class NodeSyncStatus {
        public long height;
        public int nodeIndex;
        
        @Override
        public String toString() {
            return "Node " + nodeIndex + " at height " + height;
        }
    }
    
    public enum SearchCriteria {
        TRANSACTION_ID,
        ADDRESS,
        PAYMENT_ID,
        AMOUNT,
        BLOCK_HEIGHT,
        NOTE,
        ALL
    }
    
    // Example usage with multiple nodes
    public static void main(String[] args) {
        try {
            // Setup multiple stagenet nodes
            MoneroTransactionManager manager = new MoneroTransactionManager(
                "127.0.0.1", 38082, null, null
            );
            
            // Add additional nodes (if you have multiple stagenet nodes running)
            manager.addNode("127.0.0.1", 38083, null, null);
            manager.addNode("node.moneroworld.com", 38081, null, null);
            
            System.out.println("=== Checking node sync status ===");
            Map<Integer, NodeSyncStatus> syncStatus = manager.checkNodesSyncStatus();
            for (Map.Entry<Integer, NodeSyncStatus> entry : syncStatus.entrySet()) {
                System.out.println(entry.getValue());
            }
            
            System.out.println("\n=== Checking mempools across nodes ===");
            Map<Integer, List<String>> mempools = manager.checkMempoolAcrossNodes();
            for (Map.Entry<Integer, List<String>> entry : mempools.entrySet()) {
                System.out.println("Node " + entry.getKey() + " has " + 
                                 entry.getValue().size() + " transactions in mempool");
            }
            
            System.out.println("\n=== Searching for specific transaction across all nodes ===");
            String txidToFind = "example_txid_here";
            Transaction found = manager.searchTransactionAcrossNodes(txidToFind);
            if (found != null) {
                System.out.println("Found on node " + found.nodeSource + ": " + found);
            } else {
                System.out.println("Transaction not found on any node");
            }
            
            System.out.println("\n=== Aggregating transactions from all nodes ===");
            List<Transaction> allTx = manager.aggregateTransactionsFromAllNodes();
            System.out.println("Total unique transactions across all nodes: " + allTx.size());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
