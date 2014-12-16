package com.netflix.eureka2.testkit.embedded.cluster;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import com.netflix.eureka2.client.resolver.ServerResolver;
import com.netflix.eureka2.client.resolver.ServerResolver.Server;
import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.interests.ChangeNotification.Kind;
import com.netflix.eureka2.registry.datacenter.LocalDataCenterInfo.DataCenterType;
import com.netflix.eureka2.server.config.WriteServerConfig;
import com.netflix.eureka2.testkit.embedded.cluster.EmbeddedWriteCluster.WriteClusterReport;
import com.netflix.eureka2.testkit.embedded.server.EmbeddedWriteServer;
import com.netflix.eureka2.testkit.embedded.server.EmbeddedWriteServer.WriteServerReport;
import com.netflix.eureka2.transport.EurekaTransports.Codec;
import netflix.ocelli.LoadBalancer;
import netflix.ocelli.MembershipEvent;
import netflix.ocelli.MembershipEvent.EventType;
import netflix.ocelli.loadbalancer.DefaultLoadBalancerBuilder;
import rx.Observable;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

/**
 * @author Tomasz Bak
 */
public class EmbeddedWriteCluster extends EmbeddedEurekaCluster<EmbeddedWriteServer, WriteClusterReport> {

    public static final String WRITE_SERVER_NAME = "eureka2-write";
    public static final int WRITE_SERVER_PORTS_FROM = 13000;

    private final boolean withExt;
    private final boolean withAdminUI;

    private int nextAvailablePort = WRITE_SERVER_PORTS_FROM;

    private final List<ChangeNotification<WriteServerAddress>> clusterAddresses = new ArrayList<>();
    private final PublishSubject<ChangeNotification<WriteServerAddress>> clusterAddressUpdates = PublishSubject.create();

    public EmbeddedWriteCluster(boolean withExt, boolean withAdminUI) {
        super(WRITE_SERVER_NAME);
        this.withExt = withExt;
        this.withAdminUI = withAdminUI;
    }

    @Override
    public int scaleUpByOne() {
        WriteServerAddress writeServerAddress = new WriteServerAddress("localhost", nextAvailablePort, nextAvailablePort + 1, nextAvailablePort + 2);

        WriteServerConfig config = WriteServerConfig.writeBuilder()
                .withAppName(WRITE_SERVER_NAME)
                .withVipAddress(WRITE_SERVER_NAME)
                .withDataCenterType(DataCenterType.Basic)
                .withRegistrationPort(writeServerAddress.getRegistrationPort())
                .withDiscoveryPort(writeServerAddress.getDiscoveryPort())
                .withReplicationPort(writeServerAddress.getReplicationPort())
                .withCodec(Codec.Avro)
                .withShutDownPort(nextAvailablePort + 3)
                .withWebAdminPort(nextAvailablePort + 4)
                .withReplicationRetryMillis(1000)
                .build();
        EmbeddedWriteServer newServer = newServer(config);
        newServer.start();

        servers.add(newServer);
        addReplicationPeer(writeServerAddress);

        nextAvailablePort += 10;

        return servers.size() - 1;
    }

    protected EmbeddedWriteServer newServer(WriteServerConfig config) {
        return new EmbeddedWriteServer(
                config,
                replicationPeers(),
                withExt,
                withAdminUI
        );
    }

    @Override
    public void scaleDownByOne(int idx) {
        super.scaleDownByOne(idx);
        removeReplicationPeer(idx);
    }

    @Override
    public WriteClusterReport clusterReport() {
        List<WriteServerReport> serverReports = new ArrayList<>();
        for (EmbeddedWriteServer server : servers) {
            serverReports.add(server.serverReport());
        }
        return new WriteClusterReport(serverReports);
    }

    public ServerResolver registrationResolver() {
        return getServerResolver(new Func1<WriteServerAddress, Integer>() {
            @Override
            public Integer call(WriteServerAddress writeServerAddress) {
                return writeServerAddress.getRegistrationPort();
            }
        });
    }

    public ServerResolver discoveryResolver() {
        return getServerResolver(new Func1<WriteServerAddress, Integer>() {
            @Override
            public Integer call(WriteServerAddress writeServerAddress) {
                return writeServerAddress.getDiscoveryPort();
            }
        });
    }

    public Observable<ChangeNotification<InetSocketAddress>> replicationPeers() {
        return Observable.from(clusterAddresses).concatWith(clusterAddressUpdates).map(
                new Func1<ChangeNotification<WriteServerAddress>, ChangeNotification<InetSocketAddress>>() {
                    @Override
                    public ChangeNotification<InetSocketAddress> call(ChangeNotification<WriteServerAddress> notification) {
                        WriteServerAddress data = notification.getData();
                        InetSocketAddress socketAddress = new InetSocketAddress(data.getHostName(), data.getReplicationPort());
                        switch (notification.getKind()) {
                            case Add:
                                return new ChangeNotification<InetSocketAddress>(Kind.Add, socketAddress);
                            case Modify:
                                throw new IllegalStateException("Modify not expected");
                            case Delete:
                                return new ChangeNotification<InetSocketAddress>(Kind.Delete, socketAddress);
                        }
                        return null;
                    }
                });
    }

    private ServerResolver getServerResolver(final Func1<WriteServerAddress, Integer> portFunc) {
        Observable<MembershipEvent<Server>> events = Observable.from(clusterAddresses).concatWith(clusterAddressUpdates)
                .map(new Func1<ChangeNotification<WriteServerAddress>, MembershipEvent<Server>>() {
                    @Override
                    public MembershipEvent<Server> call(ChangeNotification<WriteServerAddress> notification) {
                        WriteServerAddress endpoints = notification.getData();
                        int port = portFunc.call(endpoints);
                        switch (notification.getKind()) {
                            case Add:
                                return new MembershipEvent<>(EventType.ADD, new Server(endpoints.getHostName(), port));
                            case Modify:
                                throw new IllegalStateException("Modify not expected");
                            case Delete:
                                return new MembershipEvent<Server>(EventType.REMOVE, new Server(endpoints.getHostName(), port));
                        }
                        return null;
                    }
                });
        final LoadBalancer<Server> loadBalancer = new DefaultLoadBalancerBuilder<Server>(events).build();
        return new ServerResolver() {
            @Override
            public Observable<Server> resolve() {
                return loadBalancer.choose();
            }

            @Override
            public void close() {
                loadBalancer.shutdown();
            }
        };
    }

    private void addReplicationPeer(WriteServerAddress address) {
        clusterAddresses.add(new ChangeNotification<WriteServerAddress>(Kind.Add, address));
        clusterAddressUpdates.onNext(new ChangeNotification<WriteServerAddress>(Kind.Add, address));
    }

    private void removeReplicationPeer(int idx) {
        ChangeNotification<WriteServerAddress> addChange = clusterAddresses.remove(idx);
        clusterAddressUpdates.onNext(new ChangeNotification<WriteServerAddress>(Kind.Delete, addChange.getData()));
    }

    static class WriteServerAddress {

        private final String hostName;
        private final int registrationPort;
        private final int discoveryPort;
        private final int replicationPort;

        WriteServerAddress(String hostName, int registrationPort, int discoveryPort, int replicationPort) {
            this.hostName = hostName;
            this.registrationPort = registrationPort;
            this.discoveryPort = discoveryPort;
            this.replicationPort = replicationPort;
        }

        public String getHostName() {
            return hostName;
        }

        public int getRegistrationPort() {
            return registrationPort;
        }

        public int getDiscoveryPort() {
            return discoveryPort;
        }

        public int getReplicationPort() {
            return replicationPort;
        }
    }

    public static class WriteClusterReport {
        private final List<WriteServerReport> serverReports;

        public WriteClusterReport(List<WriteServerReport> serverReports) {
            this.serverReports = serverReports;
        }

        public List<WriteServerReport> getServerReports() {
            return serverReports;
        }
    }
}