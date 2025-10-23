package org.example;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;

//import static org.example.CLILogging.formatNewViews;


public class NodeMessageService extends MessageServiceGrpc.MessageServiceImplBase {

    private static final Logger logger = LogManager.getLogger(NodeMessageService.class);
    private final Node node;
    private final CommunicationLogger communicationLogger;
    private final MessageAuthenticator auth;

    public NodeMessageService(Node node, CommunicationLogger communicationLogger, MessageAuthenticator auth) {
        this.node = node;
        this.communicationLogger = communicationLogger;
        this.auth = auth;
    }

    // Output of the RPC executed on the server is added to the StreamObserver passed

    @Override
    public void request(MessageServiceOuterClass.ClientRequest request, StreamObserver<Empty> responseObserver) {
        communicationLogger.add(request);
        logger.info("MESSAGE: <REQUEST, ({}, {}, {}), {}, {}> received from client {}",
                request.getTransaction().getSender(),
                request.getTransaction().getReceiver(),
                request.getTransaction().getAmount(),
                request.getTimestamp(),
                request.getClientId(),
                request.getClientId()
        );

        if (!auth.verify(request)) {
            logger.warn("Invalid signature for client request from client {}", request.getClientId());
            return;
        }
        logger.info("Signature verified for client request from client {}", request.getClientId());
        // Handle the client request asynchronously
//        node.handleClientRequest(request, responseObserver);
    }

    @Override
    public void setActiveFlag(MessageServiceOuterClass.ActiveFlag request, StreamObserver<MessageServiceOuterClass.Acknowledgement> responseObserver) {
        node.setActive(request.getActiveFlag());
        MessageServiceOuterClass.Acknowledgement ack = MessageServiceOuterClass.Acknowledgement.newBuilder().setStatus(true).build();
        responseObserver.onNext(ack);
        responseObserver.onCompleted();
    }

//    @Override
//    public void prepare(MessageServiceOuterClass.PrepareMessage request, StreamObserver<MessageServiceOuterClass.PromiseMessage> responseObserver) {
//        communicationLogger.add(
//                String.format("MESSAGE: <PREPARE, <%d, %s>> received from server %s",
//                        request.getBallot().getInstance(),
//                        request.getBallot().getSenderId(),
//                        request.getBallot().getSenderId()
//                )
//        );
//        logger.info("MESSAGE: <PREPARE, <{}, {}>> received from server {}",
//                request.getBallot().getInstance(),
//                request.getBallot().getSenderId(),
//                request.getBallot().getSenderId()
//        );
//        MessageServiceOuterClass.PromiseMessage promise = node.handlePrepare(request, responseObserver);
//        if (promise == null) {
////            do nothing
//        } else {
//            responseObserver.onNext(promise);
//            responseObserver.onCompleted();
//        }
//    }
//
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
//    @Override
//    public void accept(MessageServiceOuterClass.AcceptMessage request, StreamObserver<MessageServiceOuterClass.AcceptedMessage> responseObserver) {
//        communicationLogger.add(
//                String.format("MESSAGE: <ACCEPT, <%d, %s>, %d, (%s, %s, %f)> received from server %s",
//                        request.getBallot().getInstance(),
//                        request.getBallot().getSenderId(),
//                        request.getSequenceNumber(),
//                        request.getRequest().getTransaction().getSender(),
//                        request.getRequest().getTransaction().getReceiver(),
//                        request.getRequest().getTransaction().getAmount(),
//                        request.getBallot().getSenderId())
//        );
//        logger.info("MESSAGE: <ACCEPT, <{}, {}>, {}, ({}, {}, {})> received from server {}",
//                request.getBallot().getInstance(),
//                request.getBallot().getSenderId(),
//                request.getSequenceNumber(),
//                request.getRequest().getTransaction().getSender(),
//                request.getRequest().getTransaction().getReceiver(),
//                request.getRequest().getTransaction().getAmount(),
//                request.getBallot().getSenderId());
//        responseObserver.onNext(node.handleAccept(request));
//        responseObserver.onCompleted();
//    }
//
//    @Override
//    public void commit(MessageServiceOuterClass.CommitMessage request, StreamObserver<Empty> responseObserver) {
//        communicationLogger.add(
//                String.format("MESSAGE: <COMMIT, <%d, %s>, %d, (%s, %s, %f)> received from server %s",
//                        request.getBallot().getInstance(),
//                        request.getBallot().getSenderId(),
//                        request.getSequenceNumber(),
//                        request.getRequest().getTransaction().getSender(),
//                        request.getRequest().getTransaction().getReceiver(),
//                        request.getRequest().getTransaction().getAmount(),
//                        request.getBallot().getSenderId())
//        );
//        logger.info("MESSAGE: <COMMIT <{}, {}>, {}, ({}, {}, {})> received from server {}",
//                request.getBallot().getInstance(),
//                request.getBallot().getSenderId(),
//                request.getSequenceNumber(),
//                request.getRequest().getTransaction().getSender(),
//                request.getRequest().getTransaction().getReceiver(),
//                request.getRequest().getTransaction().getAmount(),
//                request.getBallot().getSenderId());
//
//        node.handleCommitMessage(request);
//
//        responseObserver.onNext(Empty.getDefaultInstance());
//        responseObserver.onCompleted();
//    }
//
//    @Override
//    public void forwardClientRequest(MessageServiceOuterClass.ClientRequest request, StreamObserver<Empty> responseObserver) {
//        communicationLogger.add(
//                String.format("MESSAGE: <REQUEST, (%s, %s, %f), %d, %s> forwarded from another server",
//                        request.getTransaction().getSender(),
//                        request.getTransaction().getReceiver(),
//                        request.getTransaction().getAmount(),
//                        request.getTimestamp(),
//                        request.getClientId()
//                )
//        );
//        logger.info("MESSAGE: <REQUEST, ({}, {}, {}), {}, {}> forwarded from another server",
//                request.getTransaction().getSender(),
//                request.getTransaction().getReceiver(),
//                request.getTransaction().getAmount(),
//                request.getTimestamp(),
//                request.getClientId()
//        );
//
//        node.handleClientRequest(request, null);
//
//        responseObserver.onNext(Empty.getDefaultInstance());
//        responseObserver.onCompleted();
//    }
//
//    @Override
//    public void failLeader(Empty request, StreamObserver<MessageServiceOuterClass.Acknowledgement> responseObserver) {
//        logger.info("<FAIL LEADER> received from cli");
//        MessageServiceOuterClass.Acknowledgement ack;
//        if (node.getState().isLeader()) {
//            node.setActive(false);
//            logger.info("Node {} (leader) has been deactivated to simulate leader failure.", node.getServerId());
//            ack = MessageServiceOuterClass.Acknowledgement.newBuilder().setStatus(true).build();
//        } else {
//            logger.info("Node {} is not the leader. Ignoring FAIL LEADER command.", node.getServerId());
//            ack = MessageServiceOuterClass.Acknowledgement.newBuilder().setStatus(false).build();
//        }
//        responseObserver.onNext(ack);
//        responseObserver.onCompleted();
//    }

    @Override
    public void getLog(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
        String logString = CLILogging.formatLog(communicationLogger.getLogs());
        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(logString).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

//    @Override
//    public void getDB(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
//        String stateString = CLILogging.formatState(node.getClientState());
//        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(stateString).build();
//        responseObserver.onNext(response);
//        responseObserver.onCompleted();
//    }
//
//    @Override
//    public void getStatus(MessageServiceOuterClass.SequenceNumber request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
//        String statusString = mapStatus(node.getLog().getStatus(request.getSequenceNumber()));
//        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(statusString).build();
//        responseObserver.onNext(response);
//        responseObserver.onCompleted();
//    }
//
//    @Override
//    public void getNewViews(Empty request, StreamObserver<MessageServiceOuterClass.CLIResponse> responseObserver) {
//        String newViewsString = formatNewViews(node.getNewViews());
//        MessageServiceOuterClass.CLIResponse response = MessageServiceOuterClass.CLIResponse.newBuilder().setCliResponse(newViewsString).build();
//        responseObserver.onNext(response);
//        responseObserver.onCompleted();
//    }

}
