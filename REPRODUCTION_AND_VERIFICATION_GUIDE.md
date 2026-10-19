# Bitchat-Android Monero Integration: Reproduction and Verification Guide

Author: Alex Vance
Target: Bounty #201 ("Add the functionality to send Monero on Bitchat", 10.015 XMR)
Repository Reference: https://github.com/niyid/bitchat-android/tree/with-monero

***

## 1. Overview and Architecture

The Monero integration in Bitchat-Android enables users to send and receive Monero (XMR) directly within off-grid or internet-connected Bluetooth mesh chat channels.

The integration spans four primary components:
1. Native Monero Core (JNI): `WalletSuite.java` manages the underlying native C++ Monero wallet lifecycle, seed derivation, transaction construction, and daemon RPC communication.
2. Transaction Orchestration: `MoneroTransactionManager.java` handles thread-safe transaction creation, balance checks, SSL daemon connections, and hex blob generation.
3. Wire Protocol & Mesh Transport: `MoneroMessageHandler.kt` and `MoneroChatTransferManager.kt` serialize payment requests, address advertisements, and transaction IDs across the Bitchat Bluetooth mesh.
4. User Interface: `MoneroTransactionComponents.kt` and `ChatViewModel.kt` render Jetpack Compose balance cards, confirmation popups, and pending transaction bottom sheets.

***

## 2. Prerequisites and Environment Setup

### Hardware / Emulator Options
- Physical Devices: Two Android devices running Android 8.0 (API 26) or higher with Bluetooth Low Energy (BLE) enabled.
- Emulator Testing: Android Studio emulators with API 34 (x86_64 or arm64). For BLE testing on emulators, configure local port forwarding or test daemon RPC over internet while mocking mesh peers.

### Monero Daemon Connectivity
To test without spending real XMR, connect to the Monero Stagenet:
- Public Stagenet Remote Node: `stagenet.xmr-tw.org:38081` (No SSL) or `stagenet.community.xmr.to:38081`
- Local Stagenet Node: Run `monerod --stagenet --rpc-bind-ip 0.0.0.0 --confirm-external-bind` on your local network.

***

## 3. Building and Installing the APK

### Option A: Using the Automated CI Workflow
1. Push to the `with-monero` branch or dispatch the `Build and Release APKs` workflow in `.github/workflows/build-apk.yml`.
2. Download `bitchat-monero-apks.zip` from the GitHub Actions Artifacts tab.
3. Extract `app-debug.apk` and install:
```bash
adb install -r app-debug.apk
```

### Option B: Local Command-Line Build
Ensure JDK 21 and Android SDK Command-line Tools are installed:
```bash
git clone -b with-monero https://github.com/niyid/bitchat-android.git
cd bitchat-android
chmod +x gradlew
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

***

## 4. Step-by-Step Video Demonstration Reproduction

This procedure faithfully reproduces the complete workflow demonstrated in the reference video:

### Step 1: Initializing the Monero Wallet
1. Launch Bitchat on Device A (Sender) and Device B (Receiver).
2. Open Settings (gear icon in the top app bar) and navigate to "Monero Settings".
3. Enter the Daemon Host and Port:
   - Host: `stagenet.xmr-tw.org`
   - Port: `38081`
   - SSL: Toggle OFF (or ON if using an HTTPS stagenet endpoint).
4. Select "Create New Wallet" or "Restore Wallet from Seed".
5. Set a secure wallet PIN.
6. The app initializes `WalletSuite` and transitions through `IDLE -> SYNCING`. A progress indicator displays the block height synchronization status against the remote daemon.
7. Acquire Stagenet XMR for testing from a public stagenet faucet (e.g., `faucet.stagenet.xmr-tw.org`) sent to your Device A wallet address.

### Step 2: Peer Discovery and Address Exchange Over Mesh
1. Bring Device A and Device B within Bluetooth range (or ensure both are connected to the same local channel).
2. On Device B (Receiver):
   - Tap the Monero icon in the chat action bar.
   - Select "Share Address with Peer".
   - The app packages the 95-character primary address (or 106-character sub-address) into a `[MONERO_ADDR]` mesh packet and transmits it over BLE.
3. On Device A (Sender):
   - Bitchat receives the packet via `MoneroMessageHandler`.
   - The UI automatically updates: "Device B shared Monero address" appears as an authenticated system message.
   - Device B's address is cached in `ChatViewModel._peerMoneroAddresses` mapped to their peer ID.

### Step 3: Drafting the Transfer and Confirmation Modal
1. On Device A (Sender), tap the Monero icon in the chat composer bar.
2. Select "Send Monero" to open the transfer dialog.
3. The dialog automatically pre-fills Device B's Monero address.
4. Enter the amount to send (for example: `0.01` XMR).
5. The app queries `MoneroTransactionManager`:
   - Validates the recipient address format using `MoneroValidator`.
   - Converts the decimal amount to atomic piconeros (0.01 * 10^12 = 10,000,000,000 atomic units).
   - Fetches estimated network fee from the connected daemon.
6. A safety confirmation modal appears:
   - Recipient: Device B nickname + truncated address (`5...` or `7...` on stagenet).
   - Amount: `0.010000000000 XMR`.
   - Estimated Fee: `~0.000021500000 XMR`.
   - Total Debit: Amount + Fee.
7. Tap "Confirm & Send".

### Step 4: Transaction Execution and Broadcast
1. `WalletSuite` transitions state: `IDLE -> TRANSACTION`.
2. Native Monero core builds the signed transaction hex blob.
3. The transaction is submitted to the Monero daemon RPC (`/sendrawtransaction`).
4. Upon successful daemon acceptance:
   - A unique 64-character transaction hash (TxID) is returned.
   - Device A creates a local pending chat entry: "Sending 0.01 XMR... (pending)".
   - Device A broadcasts a `[MONERO_TXID]<txid>` packet over the Bluetooth mesh to Device B.

### Step 5: Recipient Detection and In-Chat Confirmation
1. Device B receives the `[MONERO_TXID]` packet.
2. Device B's background polling service queries the daemon for incoming outputs matching the TxID.
3. Once the transaction is detected in the daemon mempool:
   - Device B renders an incoming payment card: "Received 0.01 XMR from Device A (0/10 confirmations)".
   - Device B sends a `[MONERO_TX_FOUND]<txid>|0` receipt confirmation packet back to Device A.
4. Device A updates the transaction status from "pending" to "broadcasted (0/10 confirmations)".
5. As blocks are mined on the network, the confirmation counter increments until full settlement.

***

## 5. Handling Mesh Dropouts: Manual Transaction Search

If Bluetooth connectivity was temporarily interrupted when a transaction was sent:
1. Open the chat menu and tap "Pending Transactions".
2. The bottom sheet displays all unconfirmed or missing transactions.
3. Tap "Search for Transaction" (`TransactionSearchDialog`).
4. Paste the 64-character TxID.
5. The app queries the blockchain directly via the daemon RPC, re-synchronizes the output keys, and links the payment to the chat thread.

***

## 6. Code Quality and State Guard Verification

The implementation in `with-monero` resolves previous lifecycle edge cases:
- State Machine Guards: `WalletSuite.java` ensures state transitions are atomic using `compareAndSet(IDLE, TRANSACTION)`.
- Re-entrant Safe: Sync threads running in background cannot collide with active outgoing transactions.
- Zero Cleartext Secrets: Wallet keys and daemon credentials are stored strictly in Android `EncryptedSharedPreferences`.
