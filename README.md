## Linear PBFT

Implemented a variant of the Practical Byzantine
Fault Tolerance (PBFT) consensus protocol. Includes two
key components of the linear variant of the PBFT protocol: 
- the normal case operation
- the view-change routine

Utilized a basic distributed banking application to facilitate the implementation and testing of
this protocol variant.

### Components
- CLI: Interactive console tool to load transaction sets, activate servers for each set, submit transactions in order, and inspect server logs, DB state, per-sequence status, and ViewChange messages via gRPC calls.
- Node: Core PBFT server with role transitions (primary/backup), timers, executors for state/log/network/streaming/message concerns, handling PrePrepare/Prepare, Commit, ViewChange, NewView streaming, execution of committed entries in order, and client request forwarding/replies.

### Protobuf Messages and Service definitions - gRPC
- `src/main/proto/message_service.proto` : gRPC protocol and message schema for client-server and server-server RPCs: Request/Reply, PrePrepare/Prepare, Commit, ViewChange, NewView (stream), leader liveness controls, checkpoints, and CLI helpers.

### Configuration
- `src/main/resources/clientDetails.json` : Initial account universe with IDs and starting balances used to bootstrap the banking state on servers.
- `src/main/resources/serverDetails.json` : Cluster membership and networking info for servers (IDs, host, port) consumed by clients and servers to form channels and stubs.

### Test cases
- `src/main/resources/transactionSetsTest.csv` : Test scenario file supporting grouped transactions per set, live-node masks, and leader-fail markers (LF) to stress test view changes and recovery paths.
