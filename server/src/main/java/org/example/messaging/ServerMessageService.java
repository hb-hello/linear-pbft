package org.example.messaging;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.CLILogging;
import org.example.MessageServiceGrpc;
import org.example.MessageServiceOuterClass;
import org.example.ServerNode;
import org.example.crypto.MessageAuthenticator;

import static org.example.CLILogging.formatNewViews;
import static org.example.CLILogging.mapStatus;

//import static org.example.CLILogging.formatNewViews;


public class ServerMessageService extends MessageServiceGrpc.MessageServiceImplBase {

    private static final Logger logger = LogManager.getLogger(ServerMessageService.class);
    private final ServerNode serverNode;
    private final CommunicationLogger communicationLogger;
    private final MessageAuthenticator auth;

    public ServerMessageService(ServerNode serverNode, CommunicationLogger communicationLogger, MessageAuthenticator auth) {
        this.serverNode = serverNode;
        this.communicationLogger = communicationLogger;
        this.auth = auth;
    }

    // Output of the RPC executed on the server is added to the StreamObserver passed

    @Override
    public void request(MessageServiceOuterClass.ClientRequest request, StreamObserver<Empty> responseObserver) {
        communicationLogger.add(request);

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for client request from client {}", request.getClientId());
            return;
        }

        logger.info("Signature verified for client request from client {}", request.getClientId());

        serverNode.handleClientRequest(request);
    }

    @Override
    public void setActiveFlag(MessageServiceOuterClass.ActiveFlag request, StreamObserver<MessageServiceOuterClass.Acknowledgement> responseObserver) {
        serverNode.setActive(request.getActiveFlag());
        MessageServiceOuterClass.Acknowledgement ack = MessageServiceOuterClass.Acknowledgement.newBuilder().setStatus(true).build();
        responseObserver.onNext(ack);
        responseObserver.onCompleted();
    }

    @Override
    public void reset(Empty request, StreamObserver<MessageServiceOuterClass.Acknowledgement> responseObserver) {
        serverNode.reset();
        MessageServiceOuterClass.Acknowledgement ack = MessageServiceOuterClass.Acknowledgement.newBuilder().setStatus(true).build();
        responseObserver.onNext(ack);
        responseObserver.onCompleted();
    }

    @Override
    public void prePrepare(MessageServiceOuterClass.PrePrepareRequest request, StreamObserver<Empty> responseObserver) {

        //Verify inner preprepare message
        MessageServiceOuterClass.PrePrepareMessage prePrepareMessage = request.getPrePrepareMessage();
        if (!auth.verify(prePrepareMessage)) {
            logger.warn("Invalid signature for Pre-Prepare message from server {}", request.getPrePrepareMessage().getSignerId());
            return;
        }

        logger.info("Signature verified for Pre-Prepare message from server {}", request.getPrePrepareMessage().getSignerId());

        communicationLogger.add(
                String.format("MESSAGE: <PRE-PREPARE, <%d, %d>> received from server %s",
                        request.getPrePrepareMessage().getViewNumber(),
                        request.getPrePrepareMessage().getSequenceNumber(),
                        request.getPrePrepareMessage().getSignerId()
                )
        );

        serverNode.handlePrePrepare(request);
    }

    @Override
    public void prepare(MessageServiceOuterClass.PrepareMessage request, StreamObserver<Empty> responseObserver) {

        communicationLogger.add(
                String.format("MESSAGE: <PREPARE, <%d, %d, d>> received from server %s",
                        request.getViewNumber(),
                        request.getSequenceNumber(),
                        request.getSignerId()
                )
        );

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for Prepare message from server {}", request.getSignerId());
            return;
        }

        logger.info("Signature verified for Prepare message from server {}", request.getSignerId());

        serverNode.handlePrepare(request);
    }

    @Override
    public void commit(MessageServiceOuterClass.CommitMessage request, StreamObserver<Empty> responseObserver) {
        communicationLogger.add(
                String.format("MESSAGE: <COMMIT, %d, %d, d> received from server %s",
                        request.getViewNumber(),
                        request.getSequenceNumber(),
                        request.getSignerId()
                )
        );

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for Commit message from server {}", request.getSignerId());
            return;
        }

        serverNode.handleCommit(request);
    }

    public void checkpoint(MessageServiceOuterClass.CheckpointMessage request, StreamObserver<Empty> responseObserver) {
        communicationLogger.add(
                String.format("MESSAGE: <CHECKPOINT, %d> received from server %s",
                        request.getSequenceNumber(),
                        request.getSignerId()
                )
        );

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for Checkpoint message from server {}", request.getSignerId());
            return;
        }

        serverNode.handleCheckpoint(request);
    }

    public void stateRequest(MessageServiceOuterClass.StateRequestMessage request, StreamObserver<Empty> responseObserver) {
        logger.info("MESSAGE: <STATE REQUEST> received from server {}",
                request.getRequesterId()
        );
        serverNode.handleStateRequest(request.getRequesterId());
    }

    public void stateResponse(MessageServiceOuterClass.StateMessage request, StreamObserver<Empty> responseObserver) {
        logger.info("MESSAGE: <STATE RESPONSE> received from server {}",
                request.getSignerId()
        );

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for State message from server {}", request.getSignerId());
            return;
        }

        serverNode.handleStateMessage(request);
    }

    public void viewChange(MessageServiceOuterClass.ViewChangeMessage request, StreamObserver<Empty> responseObserver) {
        communicationLogger.add(
                String.format("MESSAGE: <VIEW CHANGE, %d, %d, C, P, %s> received from server %s",
                        request.getViewNumber(),
                        request.getLastStableSequenceNumber(),
                        request.getSignerId(),
                        request.getSignerId()
                )
        );

        logger.info("MESSAGE: <VIEW CHANGE, {}, {}> received from server {}",
                request.getViewNumber(),
                request.getLastStableSequenceNumber(),
                request.getSignerId()
        );

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for View Change message from server {}", request.getSignerId());
            return;
        }

        serverNode.handleViewChange(request);
    }

//    @Override
//    public void newView(MessageServiceOuterClass.NewViewMessage request, StreamObserver<MessageServiceOuterClass.AcceptedMessage> responseObserver) {
//        communicationLogger.add(
//                String.format("MESSAGE: <NEW VIEW, <%d, %s>, acceptLog(%d messages)> received from server %s",
//                        request.getBallot().getInstance(),
//                        request.getBallot().getSenderId(),
//                        request.getAcceptLogCount(),
//                        request.getBallot().getSenderId())
//        );
//        logger.info("MESSAGE: <NEW VIEW, <{}, {}>, acceptLog({} messages)> received from server {}",
//                request.getBallot().getInstance(),
//                request.getBallot().getSenderId(),
//                request.getAcceptLogCount(),
//                request.getBallot().getSenderId());
//        node.handleNewView(request, responseObserver);
//    }
//

    @Override
    public void getLog(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
        String logString = CLILogging.formatLog(communicationLogger.getLogs());
        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(logString).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getDB(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
        String stateString = serverNode.getDB();
        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(stateString).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getStatus(MessageServiceOuterClass.SequenceNumber request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
        String statusString = mapStatus(serverNode.getOperationStatus(request.getSequenceNumber())) + " for operation : " + serverNode.getOperation(request.getSequenceNumber());
        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(statusString).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
//
//    @Override
    public void getNewViews(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
        String newViewsString = formatNewViews(serverNode.getNewViews());
        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(newViewsString).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getOperationLog(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
        logger.info("Received request for operation log");
        String operationLogString = serverNode.printOperationLog();
//        logger.info("Sending operation log:\n{}", operationLogString);
        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(operationLogString).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

}
