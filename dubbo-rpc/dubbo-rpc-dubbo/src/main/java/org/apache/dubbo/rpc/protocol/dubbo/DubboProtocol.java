/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.PortUnificationExchanger;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_ERROR_CLOSE_CLIENT;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_ERROR_CLOSE_SERVER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_FAILED_REFER_INVOKER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_UNSUPPORTED;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;

    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";

    /**
     * <host:port,Exchanger>
     * Map<String, List<ReferenceCountExchangeClient>
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();

    private static final Object PENDING_OBJECT = new Object();

    private final AtomicBoolean destroyed = new AtomicBoolean();

    private final ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                    + (message == null ? null : (message.getClass().getName() + ": " + message))
                    + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            Invoker<?> invoker = getInvoker(channel, inv);
            inv.setServiceModel(invoker.getUrl().getServiceModel());
            // switch TCCL
            if (invoker.getUrl().getServiceModel() != null) {
                Thread.currentThread().setContextClassLoader(invoker.getUrl().getServiceModel().getClassLoader());
            }
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachmentWithoutConvert(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(PROTOCOL_FAILED_REFER_INVOKER, "", "", new IllegalStateException("The methodName " + inv.getMethodName()
                        + " not found in callback service interface ,invoke will be ignored."
                        + " please update the api interface. url is:"
                        + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext.getServiceContext().setRemoteAddress(channel.getRemoteAddress());
            Result result = invoker.invoke(inv);
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    if (Boolean.TRUE.toString().equals(invocation.getAttachment(STUB_EVENT_KEY))) {
                        tryToGetStubService(channel, invocation);
                    }
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn(PROTOCOL_FAILED_REFER_INVOKER, "", "", "Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private void tryToGetStubService(Channel channel, Invocation invocation) throws RemotingException {
            try {
                Invoker<?> invoker = getInvoker(channel, invocation);
            } catch (RemotingException e) {
                String serviceKey = serviceKey(
                    0,
                    (String) invocation.getObjectAttachmentWithoutConvert(PATH_KEY),
                    (String) invocation.getObjectAttachmentWithoutConvert(VERSION_KEY),
                    (String) invocation.getObjectAttachmentWithoutConvert(GROUP_KEY)
                );
                throw new RemotingException(channel, "The stub service[" + serviceKey + "] is not found, it may not be exported yet");
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(url.getServiceModel(), method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getGroup());
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getVersion());
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
    }

    /**
     * @deprecated Use {@link DubboProtocol#getDubboProtocol(ScopeModel)} instead
     */
    @Deprecated
    public static DubboProtocol getDubboProtocol() {
        return (DubboProtocol) ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME, false);
    }

    public static DubboProtocol getDubboProtocol(ScopeModel scopeModel) {
        return (DubboProtocol) scopeModel.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME, false);
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
            NetUtils.filterLocalHost(channel.getUrl().getIp())
                .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke;
        boolean isStubServiceInvoke;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getObjectAttachmentWithoutConvert(PATH_KEY);

        //if it's stub service on client side(after enable stubevent, usually is set up onconnect or ondisconnect method)
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachmentWithoutConvert(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            //when a stub service export to local, it usually can't be exposed to port
            port = 0;
        }

        // if it's callback service on client side
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachmentWithoutConvert(CALLBACK_SERVICE_KEY);
            inv.setObjectAttachment(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(
            port,
            path,
            (String) inv.getObjectAttachmentWithoutConvert(VERSION_KEY),
            (String) inv.getObjectAttachmentWithoutConvert(GROUP_KEY)
        );
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + getInvocationWithoutData(inv));
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        checkDestroyed();
        // 服务提供者的url 参考例子dubbo://192.168.1.9:20880/link.elastic.dubbo.entity.DemoService?anyhost=true&application=dubbo-demo-api-provider&background=false&bind.ip=192.168.1.9&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=link.elastic.dubbo.entity.DemoService&methods=sayHello,sayHelloAsync&pid=6043&release=3.0.8&service-name-mapping=true&side=provider&timestamp=1654224285437
        URL url = invoker.getUrl();

        // export service.
        String key = serviceKey(url);
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        //export a stub service for dispatching event
        // stub配置校验
        boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        boolean isCallbackService = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackService) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(PROTOCOL_UNSUPPORTED, "", "", "consumer [" + url.getParameter(INTERFACE_KEY) +
                        "], has set stub proxy support event ,but no stub methods founded.");
                }

            }
        }

        // 创建服务开启服务端口
        openServer(url);
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
        checkDestroyed();
        // find server.  地址作为key这里是192.168.1.9:20880
        String key = url.getAddress();
        // client can export a service which only for server to invoke
        // 默认提供者开启服务，消费者是不能开启服务的
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);

        if (isServer) {
            // 协议服务器 下面一个双重校验锁检查，如果为空则创建服务
            ProtocolServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        serverMap.put(key, createServer(url));
                        return;
                    }
                }
            }

            // server supports reset, use together with override
            server.reset(url);
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is destroyed");
        }
    }

    private ProtocolServer createServer(URL url) {
        url = URLBuilder.from(url)
            // send readonly event when server closes, it's enabled by default
            .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
            // enable heartbeat by default
            .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
            .addParameter(CODEC_KEY, DubboCodec.NAME)
            .build();

        //这里服务端使用的网络库这里是默认值netty
        String transporter = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);
        if (StringUtils.isNotEmpty(transporter) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(transporter)) {
            throw new RpcException("Unsupported server type: " + transporter + ", url: " + url);
        }

        ExchangeServer server;
        try {
            // 这个方法会绑定端口，关于交换器与传输网络层到后面统一说
            // 这里通过绑定url和请求处理器来创建交换器对象
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        transporter = url.getParameter(CLIENT_KEY);
        if (StringUtils.isNotEmpty(transporter) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(transporter)) {
            throw new RpcException("Unsupported client type: " + transporter);
        }

        DubboProtocolServer protocolServer = new DubboProtocolServer(server);
        loadServerProperties(protocolServer);
        return protocolServer;
    }


    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        checkDestroyed();
        return protocolBindingRefer(type, url);
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        checkDestroyed();
        // optimizer配置  序列化优化配置
        optimizeSerialization(url);

        // create rpc invoker.
        // DubboInvoker调用器对象创建 这里要重点关注的方法是getClients.
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);

        return invoker;
    }

    private ExchangeClient[] getClients(URL url) {
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        // whether to share connection
        // if not configured, connection is shared, otherwise, one connection for one service

        // 是否共享连接
        // 如果未配置，则共享连接，否则，一个服务一个连接
        if (connections == 0) {
            /*
             * The xml configuration should have a higher priority than properties.
             */
            String shareConnectionsStr = StringUtils.isBlank(url.getParameter(SHARE_CONNECTIONS_KEY, (String) null))
                ? ConfigurationUtils.getProperty(url.getOrDefaultApplicationModel(), SHARE_CONNECTIONS_KEY, DEFAULT_SHARE_CONNECTIONS)
                : url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            // 没有配置 默认则为 1
            connections = Integer.parseInt(shareConnectionsStr);

            // 共享Client
            List<ReferenceCountExchangeClient> shareClients = getSharedClient(url, connections);
            ExchangeClient[] clients = new ExchangeClient[connections];
            Arrays.setAll(clients, shareClients::get);
            return clients;
        }

        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = initClient(url);
        }
        return clients;
    }

    /**
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();

        // 先从缓存获取 共享Client
        Object clients = referenceClientMap.get(key);
        if (clients instanceof List) {
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;
            if (checkClientCanUse(typedClients)) {
                // 批量增加引用计数，如果我们创建新的调用者共享相同的连接，连接将在没有任何引用的情况下关闭
                batchClientRefIncr(typedClients);
                return typedClients;
            }
        }

        List<ReferenceCountExchangeClient> typedClients = null;

        synchronized (referenceClientMap) {
            for (; ; ) {
                // guarantee just one thread in loading condition. And Other is waiting It had finished.
                // 保证只有一个线程处于加载状态。而其他正在等待它已经完成了。
                clients = referenceClientMap.get(key);

                if (clients instanceof List) {
                    typedClients = (List<ReferenceCountExchangeClient>) clients;
                    if (checkClientCanUse(typedClients)) {
                        batchClientRefIncr(typedClients);
                        return typedClients;
                    } else {
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }
                } else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        try {
            // connectNum must be greater than or equal to 1
            // 长连接数量 默认为1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            // 如果客户端为空，则第一次初始化为
            if (CollectionUtils.isEmpty(typedClients)) {
                // 主要看这一行 构建客户端
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);
            } else {
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    // 如果列表中有一个客户端不再可用，请创建一个新客户端来替换他。
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }
        } finally {
            synchronized (referenceClientMap) {
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    // 这里key为 IP:端口 值为当前客户端 一个IP:端口的服务提供者会缓存一个连接列表
                    referenceClientMap.put(key, typedClients);
                }
                referenceClientMap.notifyAll();
            }
        }
        return typedClients;
    }

    /**
     * Check if the client list is all available
     *
     * 检查客户端列表是否全部可用
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        // As long as one client is not available, you need to replace the unavailable client with the available one.
        // 只要有一个客户端不可用，就需要将不可用的客户端替换为可用的客户端。
        return referenceCountExchangeClients.stream()
            .noneMatch(referenceCountExchangeClient -> referenceCountExchangeClient == null
                || referenceCountExchangeClient.getCount() <= 0 || referenceCountExchangeClient.isClosed());
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     * 增加引用计数，如果我们创建新的调用者共享相同的连接，连接将在没有任何引用的情况下关闭
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }
        referenceCountExchangeClients.stream()
            .filter(Objects::nonNull)
            .forEach(ReferenceCountExchangeClient::incrementAndGetCount);
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        // 初始化客户端对象
        ExchangeClient exchangeClient = initClient(url);
        // 包装交换器客户端对象
        ReferenceCountExchangeClient client = new ReferenceCountExchangeClient(exchangeClient, DubboCodec.NAME);
        // read configs
        int shutdownTimeout = ConfigurationUtils.getServerShutdownTimeout(url.getScopeModel());
        client.setShutdownWaitTime(shutdownTimeout);
        return client;
    }

    /**
     * Create new connection
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {
        /*
         * Instance of url is InstanceAddressURL, so addParameter actually adds parameters into ServiceInstance,
         * which means params are shared among different services. Since client is shared among services this is currently not a problem.
         *
         * url 的实例是 InstanceAddressURL，因此 addParameter 实际上将参数添加到 ServiceInstance 中，
         * 这意味着参数在不同的服务之间共享。由于客户端在服务之间共享，因此目前这不是问题
         */
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // BIO is not allowed since it has severe performance issue.
        // 不允许使用 BIO，因为它存在严重的性能问题。
        if (StringUtils.isNotEmpty(str) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                " supported client type is " + StringUtils.join(url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        try {
            // Replace InstanceAddressURL with ServiceConfigURL.
            url = new ServiceConfigURL(DubboCodec.NAME, url.getUsername(), url.getPassword(), url.getHost(), url.getPort(), url.getPath(), url.getAllParameters());
            url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
            // enable heartbeat by default
            url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

            // connection should be lazy
            // 创建连接
            return url.getParameter(LAZY_CONNECT_KEY, false)
                ? new LazyConnectExchangeClient(url, requestHandler)
                : Exchangers.connect(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Destroying protocol [" + this.getClass().getSimpleName() + "] ...");
        }
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Closing dubbo server: " + server.getLocalAddress());
                }

                server.close(getServerShutdownTimeout(protocolServer));

            } catch (Throwable t) {
                logger.warn(PROTOCOL_ERROR_CLOSE_SERVER, "", "", "Close dubbo server [" + server.getLocalAddress() + "] failed: " + t.getMessage(), t);
            }
        }
        serverMap.clear();

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }
        PortUnificationExchanger.close();
        referenceClientMap.clear();

        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(client.getShutdownWaitTime());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(PROTOCOL_ERROR_CLOSE_CLIENT, "", "", t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}
