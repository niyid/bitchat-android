# BitChat Android Upstream Migration Guide

## Executive Summary

The upstream project has introduced breaking changes across multiple areas:
- **Bluetooth API changes**: Constructor signatures modified, peer verification added
- **Message routing**: New abstract methods for sync handling
- **Type system changes**: `NostrIdentity` changed from String to object
- **New message types**: VERIFY_CHALLENGE, VERIFY_RESPONSE, FILE_TRANSFER
- **UI dependencies**: Compose LiveData extensions not included by default

## Quick Start

1. Run the automated fix script:
   ```bash
   ./fix_rebase.sh
   ```

2. Review the generated templates and apply manual fixes

3. Build incrementally:
   ```bash
   ./gradlew clean build
   ```

## Detailed Migration Steps

### 1. Dependency Updates

#### build.gradle.kts (app/build.gradle.kts)

```kotlin
dependencies {
    // Add this for Compose LiveData support
    implementation("androidx.compose.runtime:runtime-livedata:1.6.0")
    
    // Ensure you have the latest versions of:
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
}
```

### 2. Bluetooth API Migration

#### BluetoothConnectionManager.kt

**Before:**
```kotlin
class BluetoothConnectionManager(
    private val context: Context, 
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) {
    private val packetBroadcaster = BluetoothPacketBroadcaster(
        connectionScope, 
        connectionTracker, 
        fragmentManager, 
        myPeerID  // ❌ This parameter removed
    )
    
    private val serverManager = BluetoothGattServerManager(
        context, 
        connectionScope, 
        connectionTracker, 
        permissionManager, 
        powerManager, 
        componentDelegate, 
        myPeerID  // ❌ This parameter removed
    )
}
```

**After:**
```kotlin
class BluetoothConnectionManager(
    private val context: Context, 
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) {
    private val packetBroadcaster = BluetoothPacketBroadcaster(
        connectionScope, 
        connectionTracker, 
        fragmentManager
        // ✓ myPeerID removed - now handled internally
    )
    
    private val serverManager = BluetoothGattServerManager(
        context, 
        connectionScope, 
        connectionTracker, 
        permissionManager, 
        powerManager, 
        componentDelegate
        // ✓ myPeerID removed - now handled internally
    )
}
```

**Rationale:** The peer ID is now managed internally by these components, likely passed through a different mechanism (perhaps via context or shared state).

#### BluetoothMeshService.kt

**Issue at line 48:**
```kotlin
// Before - missing appContext
private val connectionManager = BluetoothConnectionManager(
    context,
    myPeerID,
    fragmentManager
)

// After - add appContext parameter
private val connectionManager = BluetoothConnectionManager(
    context,
    myPeerID,
    fragmentManager,
    appContext = applicationContext  // Add this
)
```

### 3. New Abstract Methods Implementation

#### Verification Callbacks (BluetoothMeshService.kt ~line 158)

```kotlin
private val componentDelegate = object : BluetoothConnectionManagerDelegate {
    override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
        // Existing implementation
    }
    
    override fun onDeviceConnected(device: BluetoothDevice) {
        // Existing implementation
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        // Existing implementation
    }
    
    override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
        // Existing implementation
    }
    
    // ✓ NEW: Add these verification methods
    override fun onVerifyChallengeReceived(
        peerID: String, 
        payload: ByteArray, 
        timestampMs: Long
    ) {
        Log.d(TAG, "Verification challenge from $peerID")
        
        // Decode the challenge
        try {
            val challenge = VerificationChallenge.decode(payload)
            
            // Store pending verification
            pendingVerifications[peerID] = VerificationState(
                peerID = peerID,
                challenge = challenge,
                timestamp = timestampMs,
                state = VerificationState.CHALLENGE_RECEIVED
            )
            
            // Notify UI layer
            verificationEventFlow.emit(
                VerificationEvent.ChallengeReceived(peerID, challenge)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode verification challenge", e)
        }
    }
    
    override fun onVerifyResponseReceived(
        peerID: String, 
        payload: ByteArray, 
        timestampMs: Long
    ) {
        Log.d(TAG, "Verification response from $peerID")
        
        try {
            val response = VerificationResponse.decode(payload)
            
            // Validate the response against our challenge
            val pendingVerification = pendingVerifications[peerID]
            if (pendingVerification != null && 
                pendingVerification.validateResponse(response)) {
                
                // Mark peer as verified
                markPeerVerified(peerID)
                
                // Update state
                pendingVerifications.remove(peerID)
                
                // Notify UI
                verificationEventFlow.emit(
                    VerificationEvent.VerificationComplete(peerID, success = true)
                )
            } else {
                Log.w(TAG, "Invalid verification response from $peerID")
                verificationEventFlow.emit(
                    VerificationEvent.VerificationComplete(peerID, success = false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process verification response", e)
        }
    }
}
```

#### Message Router Callbacks (BluetoothMeshService.kt ~line 305)

```kotlin
private val routerDelegate = object : MessageRouterDelegate {
    // Existing methods...
    
    // ✓ NEW: Add sync handling
    override fun handleRequestSync(routed: RoutedPacket) {
        Log.d(TAG, "Sync request from ${routed.sourcePeerID}")
        
        // Get the sync payload
        val syncRequest = try {
            SyncRequest.decode(routed.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode sync request", e)
            return
        }
        
        // Determine what data to sync
        val syncData = when (syncRequest.type) {
            SyncType.MESSAGES -> {
                // Get messages after the requested timestamp
                messageRepository.getMessagesSince(
                    syncRequest.lastTimestamp,
                    limit = 100
                )
            }
            SyncType.PEERS -> {
                // Get peer list
                peerRepository.getAllPeers()
            }
            SyncType.PROFILE -> {
                // Get profile data
                profileRepository.getMyProfile()
            }
            else -> {
                Log.w(TAG, "Unknown sync type: ${syncRequest.type}")
                return
            }
        }
        
        // Send sync response
        val response = SyncResponse(
            requestId = syncRequest.id,
            data = syncData
        )
        
        sendToPeer(
            routed.sourcePeerID,
            RoutedPacket(
                sourcePeerID = myPeerID,
                destPeerID = routed.sourcePeerID,
                messageType = MessageType.SYNC_RESPONSE,
                payload = response.encode()
            )
        )
    }
    
    // ✓ NEW: Direct peer messaging
    override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        return try {
            // Use the connection manager to send
            val success = bluetoothConnectionManager.sendPacket(peerID, routed)
            
            if (!success) {
                // Queue for later if peer not connected
                messageQueue.enqueue(peerID, routed)
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to peer $peerID", e)
            
            // Queue for retry
            messageQueue.enqueue(peerID, routed)
            
            false
        }
    }
}
```

### 4. Exhaustive When Expressions

#### NostrGeohashService.kt (lines 394, 1363)

**Before:**
```kotlin
when (messageType) {
    MessageType.TEXT -> handleTextMessage(message)
    MessageType.MEDIA -> handleMediaMessage(message)
    // ❌ Missing new message types causes compilation error
}
```

**After:**
```kotlin
when (messageType) {
    MessageType.TEXT -> handleTextMessage(message)
    MessageType.MEDIA -> handleMediaMessage(message)
    
    // ✓ Add new verification message types
    MessageType.VERIFY_CHALLENGE -> {
        // Forward to verification handler
        val challenge = VerificationChallenge.decode(message.payload)
        verificationHandler.handleChallenge(
            peerID = message.senderPeerID,
            challenge = challenge,
            timestamp = message.timestamp
        )
    }
    
    MessageType.VERIFY_RESPONSE -> {
        // Forward to verification handler
        val response = VerificationResponse.decode(message.payload)
        verificationHandler.handleResponse(
            peerID = message.senderPeerID,
            response = response,
            timestamp = message.timestamp
        )
    }
    
    // ✓ Add file transfer support
    MessageType.FILE_TRANSFER -> {
        // Forward to file transfer handler
        val fileTransfer = FileTransferMessage.decode(message.payload)
        fileTransferHandler.handleTransfer(
            peerID = message.senderPeerID,
            transfer = fileTransfer,
            timestamp = message.timestamp
        )
    }
    
    else -> {
        Log.w(TAG, "Unknown message type: $messageType")
        // Optionally handle unknown types gracefully
    }
}
```

### 5. Type System Changes

#### NostrIdentity Migration (MessageRouter.kt line 68)

**Before:**
```kotlin
// String-based identity
val peerIdentity: String = peerNostrKey
routedPacket.verifySignature(peerIdentity)
```

**After:**
```kotlin
// Object-based identity
val peerIdentity: NostrIdentity = NostrIdentity.fromHex(peerNostrKey)
// OR if you have the npub format:
// val peerIdentity: NostrIdentity = NostrIdentity.fromNpub(peerNpub)

routedPacket.verifySignature(peerIdentity)
```

**NostrIdentity API:**
```kotlin
// Common usage patterns
val identity = NostrIdentity.fromHex(hexPubkey)
val identity = NostrIdentity.fromNpub(npubString)

// Access properties
val hexKey: String = identity.hex
val npub: String = identity.npub
val bytes: ByteArray = identity.bytes

// Comparison
if (identity == otherIdentity) { /* ... */ }
```

### 6. UI Layer Fixes

#### Compose LiveData Integration

**All files using `observeAsState`:**

```kotlin
// Add this import at the top
import androidx.compose.runtime.livedata.observeAsState

@Composable
fun ChatHeader(viewModel: ChatViewModel) {
    // Now this will work
    val connectionState by viewModel.connectionState.observeAsState(initial = ConnectionState.DISCONNECTED)
    val peerCount by viewModel.peerCount.observeAsState(initial = 0)
    val latestMessage by viewModel.latestMessage.observeAsState()
    
    // Use the observed values
    Text("Connected: ${connectionState == ConnectionState.CONNECTED}")
    Text("Peers: $peerCount")
}
```

#### Regex MatchGroup (ChatHeader.kt line 273)

**Before:**
```kotlin
val groupValue: String? = matchGroup  // ❌ Type mismatch
```

**After:**
```kotlin
val groupValue: String? = matchGroup?.value  // ✓ Extract the value
```

### 7. Removed Methods - Finding Replacements

#### updatePeerNostrKey (MessageHandler.kt:558)

**Possible replacements to investigate:**

```kotlin
// Old API
messageHandler.updatePeerNostrKey(peerID, nostrKey)

// New API - likely one of these:
peerManager.updatePeerIdentity(peerID, NostrIdentity.fromHex(nostrKey))
// OR
peerRepository.setPeerNostrKey(peerID, nostrKey)
// OR
verificationManager.setPeerKey(peerID, nostrKey)
```

**How to find the correct replacement:**
1. Search the codebase for `NostrKey` or `updatePeer`
2. Check the PeerManager or VerificationManager classes
3. Look for methods that accept peerID and a key parameter

#### onDeviceDisconnected (BluetoothMeshService.kt:369)

**Investigation needed:**

```kotlin
// Old
delegate?.onDeviceDisconnected(device)

// Possible new patterns:
// 1. Merged into connection state callback
delegate?.onConnectionStateChanged(device, ConnectionState.DISCONNECTED)

// 2. Removed entirely - check if handled elsewhere
// The connection tracker might handle this automatically now

// 3. Check if there's a new callback name
delegate?.onPeerDisconnected(device.address)
```

#### Verification Methods (VerificationHandler.kt)

```kotlin
// Old API
verificationHandler.sendChallenge(peerID, challenge)
verificationHandler.sendResponse(peerID, response)
val pubKey = verificationHandler.getNoisePublicKey()

// New API - check VerificationManager or similar class
verificationManager.initiateVerification(peerID)
verificationManager.respondToChallenge(peerID, response)
val pubKey = cryptoManager.getMyPublicKey()
```

### 8. SidebarComponents.kt Fixes

#### Property Access Issues (lines 386-421)

**Before:**
```kotlin
val messages = items.keys()  // ❌ keys is a property, not a function
val filtered = messages.startsWith(prefix)  // ❌ Type issues
val sender = message.sender  // ❌ Property doesn't exist
val timestamp = message.timestamp  // ❌ Property doesn't exist
```

**After:**
```kotlin
// Fix 1: keys is a property
val messages = items.keys  // ✓ No parentheses

// Fix 2: Need to examine the actual data structure
// If items is Map<String, Message>:
val messageList = items.values.toList()
val filtered = messageList.filter { msg ->
    msg.id.startsWith(prefix)
}

// Fix 3: Check new message object properties
// The message object structure has changed:
data class Message(
    val id: String,
    val from: String,  // ← was 'sender'
    val sentAt: Long,   // ← was 'timestamp'
    val content: String,
    // ...
)

// Update all references:
val sender = message.from  // ✓
val timestamp = message.sentAt  // ✓
```

**Complete example:**
```kotlin
@Composable
fun MessageList(
    messages: Map<String, Message>,
    prefix: String,
    onMessageClick: (String) -> Unit
) {
    // Get messages as list
    val messageList = messages.values
        .filter { it.id.startsWith(prefix) }
        .sortedByDescending { it.sentAt }
    
    LazyColumn {
        items(messageList.size) { index ->
            val message = messageList[index]
            MessageItem(
                messageId = message.id,
                senderName = message.from,  // ← Updated property
                timestamp = message.sentAt,  // ← Updated property
                content = message.content,
                onClick = { onMessageClick(message.id) }
            )
        }
    }
}
```

### 9. Debug Settings (DebugSettingsSheet.kt)

**Before:**
```kotlin
val isDirect = message.isDirect  // ❌ Property removed
```

**After:**
```kotlin
// Option 1: Check message type
val isDirect = message.type == MessageType.DIRECT

// Option 2: Check if from peer object
val isDirect = peer?.isDirect ?: false

// Option 3: Infer from context
val isDirect = message.recipientCount == 1
```

## Testing Strategy

### 1. Unit Tests
```kotlin
class MigrationTest {
    @Test
    fun testNostrIdentityConversion() {
        val hexKey = "abc123..."
        val identity = NostrIdentity.fromHex(hexKey)
        assertEquals(hexKey, identity.hex)
    }
    
    @Test
    fun testVerificationCallbacks() {
        val delegate = object : BluetoothConnectionManagerDelegate {
            var challengeReceived = false
            
            override fun onVerifyChallengeReceived(
                peerID: String,
                payload: ByteArray,
                timestampMs: Long
            ) {
                challengeReceived = true
            }
            
            // ... other methods
        }
        
        // Test the callback
        delegate.onVerifyChallengeReceived("peer1", byteArrayOf(), 123L)
        assertTrue(delegate.challengeReceived)
    }
}
```

### 2. Integration Tests
```bash
# Build incrementally
./gradlew clean
./gradlew assembleDebug

# Run specific test suites
./gradlew test
./gradlew connectedAndroidTest
```

### 3. Manual Testing Checklist
- [ ] Bluetooth connections work
- [ ] Messages send/receive correctly
- [ ] Peer verification flow works
- [ ] File transfers initiate
- [ ] UI displays correctly
- [ ] No crashes on common operations

## Common Pitfalls

### 1. Import Hell
Make sure you're importing from the correct packages:
```kotlin
// Wrong - old package
import com.bitchat.old.MessageType

// Right - new package
import com.bitchat.protocol.MessageType
```

### 2. Nullable Types
The new API might have different nullability:
```kotlin
// Old - non-null
val key: String = peer.nostrKey

// New - nullable
val key: String? = peer.identity?.hex
```

### 3. Coroutine Scope Changes
Check if coroutine scopes have changed:
```kotlin
// Old
GlobalScope.launch { /* ... */ }

// New - use proper scopes
viewModelScope.launch { /* ... */ }
lifecycleScope.launch { /* ... */ }
```

## Resources

- **Upstream Changelog**: Check for CHANGELOG.md or MIGRATION.md
- **API Documentation**: Look for docs/ folder in upstream
- **Example Projects**: Check if upstream has sample apps
- **Issue Tracker**: Search for "breaking changes" issues

## Rollback Plan

If migration proves too complex:

1. **Backup current state**:
   ```bash
   git branch rebase-backup
   git push origin rebase-backup
   ```

2. **Alternative: Pin to older version**:
   ```kotlin
   // In build.gradle.kts
   implementation("com.upstream:library:older-version")
   ```

3. **Gradual migration**:
   - Keep old APIs in compatibility layer
   - Migrate module by module
   - Feature flags for new vs old code paths

## Completion Checklist

- [ ] All dependencies updated
- [ ] Constructor calls fixed
- [ ] Abstract methods implemented
- [ ] Exhaustive when expressions complete
- [ ] Type conversions updated
- [ ] Removed methods replaced
- [ ] UI layer fixed (observeAsState)
- [ ] Property access updated
- [ ] Build succeeds without errors
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Manual testing complete
- [ ] Code review done
- [ ] Documentation updated

## Support

If you encounter issues:

1. Check upstream GitHub issues
2. Review upstream documentation
3. Compare with upstream example code
4. Ask in upstream community channels
5. File a detailed issue if blocked

Good luck with the migration! 🚀
