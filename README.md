## Byzantine Fault Tolerant PBFT based Banking Application 

Implemented a variant of the Practical Byzantine Fault Tolerance (PBFT) consensus protocol across a 7-node cluster. Includes two key components of the linear variant of the PBFT protocol: 
- the normal case operation
- the view-change routine

Additional features: Added Threshold Signature Scheme in authentication layer, included stable checkpointing of the log, introduced a collector node reducing number of messages exchanged to scale linearly instead of quadratically.

**CAP Theorem:** System prioritizes -
- Consistency: Client receives reply only after consensus (availability takes a hit if no consensus can be achieved)
- Partition Tolerance: System continues working with network failures (up to $f=2$ nodes)

### Components
- CLI: Interactive console tool to load transaction sets, activate servers for each set, submit transactions in order, and inspect server logs, DB state, per-sequence status, and ViewChange messages via gRPC calls.
- Node: Core PBFT server with role transitions (primary/backup), timers, executors for state/log/network/streaming/message concerns, handling PrePrepare/Prepare, Commit, ViewChange, NewView streaming, execution of committed entries in order, and clientNode request forwarding/replies.

### Protobuf Messages and Service definitions - gRPC
- `src/main/proto/message_service.proto` : gRPC protocol and message schema for clientNode-server and server-server RPCs: Request/Reply, PrePrepare/Prepare, Commit, ViewChange, NewView, leader liveness controls, checkpoints, and CLI helpers.

### Configuration
- `src/main/resources/clientDetails.json` : Initial account universe with IDs and starting balances used to bootstrap the banking state on servers.
- `src/main/resources/serverDetails.json` : Cluster membership and networking info for servers (IDs, host, port) consumed by clients and servers to form channels and stubs.

### Test cases
- `src/main/resources/transactionSetsTest.csv` : Test scenario file supporting grouped transactions per set, live-serverNode masks, and leader-fail markers (LF) to stress test view changes and recovery paths.

### Credits & Sources
- gRPC Java Documentation: https://grpc.io/docs/languages/java/
- Protocol Buffers Documentation: https://developers.google.com/protocol-buffers/docs/javat
- Paxos Algorithm: https://lamport.azurewebsites.net/pubs/paxos-simple.pdf
- Oracle docs for Java: https://docs.oracle.com/en/java/
- Stack Overflow for community support and problem-solving.
- - Great resource for completable futures - https://www.youtube.com/watch?v=9ueIL0SwEWI

### Use of AI
- AI tools like _ChatGPT_ & _Claude_ were used to assist in code generation, debugging, and optimization.
- All AI-generated content was reviewed and modified to ensure accuracy and relevance to the project requirements.

### External libraries used
- gRPC Java for message passing and RPC framework.
- Protocol Buffers for message serialization.
- Jackson for JSON parsing.
- SLF4J for logging.
- Maven for the build system and dependency management.
- OpenCSV for CSV parsing.
- Supranational BLST for Threshold Signature Scheme

______________

### Notes

**Observations**: This was implemented for a distributed systems course in grad school. My second project in Java - tried to embrace structured code and DRY principles, and hopefully made fewer mistakes than before!

**Skills I picked up**: 
- DRY principles, consolidated code repetition into common interfaces, base classes to be inherited, static helper classes, etc.
- Used Java functional interfaces - consumers, runnables, callables, etc.
- Adding an authentication layer to messages and trying both public key signature and threshold signature scheme
- Looking at logs for hours to debug even more subtle race conditions
- Converting a paper / algorithm into robust code
