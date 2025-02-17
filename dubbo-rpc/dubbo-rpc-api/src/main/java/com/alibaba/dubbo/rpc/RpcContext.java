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
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.threadlocal.InternalThreadLocal;
import com.alibaba.dubbo.common.utils.NetUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread local context. (API, ThreadLocal, ThreadSafe)
 * <p>
 * Note: RpcContext is a temporary state holder. States in RpcContext changes every time when request is sent or received.
 * For example: A invokes B, then B invokes C. On service B, RpcContext saves invocation info from A to B before B
 * starts invoking C, and saves invocation info from B to C after B invokes C.
 * <p>
 * <p>
 * 注意：RpcContext 是一个临时的状态持有者。每次发送或接收请求时，RpcContext 中的状态都会发生变化。
 * 例如：A 调用 B，然后 B 调用 C。在服务 B 上，RpcContext 在 B 开始调用 C 之前将 A 的调用信息保存到 B，
 * 并在 B 调用 C 之后将 B 的调用信息保存到 C。
 *
 * @export
 * @see com.alibaba.dubbo.rpc.filter.ContextFilter
 */
public class RpcContext {

    /**
     * use internal thread local to improve performance
     * 本地上下文，使用本地内部线程来提高性能
     */
    private static final InternalThreadLocal<RpcContext> LOCAL = new InternalThreadLocal<RpcContext>() {
        @Override
        protected RpcContext initialValue() {
            return new RpcContext();
        }
    };

    /**
     * 服务上下文
     */
    private static final InternalThreadLocal<RpcContext> SERVER_LOCAL = new InternalThreadLocal<RpcContext>() {
        @Override
        protected RpcContext initialValue() {
            return new RpcContext();
        }
    };

    /**
     * 附加值集合
     */
    private final Map<String, String> attachments = new HashMap<String, String>();
    /**
     * 上下文值
     */
    private final Map<String, Object> values = new HashMap<String, Object>();
    /**
     * 线程结果
     */
    private Future<?> future;

    /**
     * URL集合
     */
    private List<URL> urls;

    /**
     * 当前的URL
     */
    private URL url;

    /**
     * 方法名称
     */
    private String methodName;

    /**
     * 参数类型集合
     */
    private Class<?>[] parameterTypes;

    /**
     * 参数集合
     */
    private Object[] arguments;

    /**
     * 本地地址
     */
    private InetSocketAddress localAddress;

    /**
     * 远程地址
     */
    private InetSocketAddress remoteAddress;


    @Deprecated
    private List<Invoker<?>> invokers;  // 实体域集合
    @Deprecated
    private Invoker<?> invoker;         // 实体域
    @Deprecated
    private Invocation invocation;      // 会话域

    // now we don't use the 'values' map to hold these objects
    // we want these objects to be as generic as possible
    // 现在我们不使用“值”映射来保存这些对象，我们希望这些对象尽可能通用
    /**
     * 请求
     */
    private Object request;
    /**
     * 响应
     */
    private Object response;

    protected RpcContext() {
    }

    /**
     * get server side context.
     * <p>
     * 获取服务器端上下文。
     *
     * @return server context
     */
    public static RpcContext getServerContext() {
        return SERVER_LOCAL.get();
    }

    /**
     * remove server side context.
     * <p>
     * 删除服务器端上下文。
     *
     * @see com.alibaba.dubbo.rpc.filter.ContextFilter
     */
    public static void removeServerContext() {
        SERVER_LOCAL.remove();
    }

    /**
     * get context.
     *
     * @return context
     */
    public static RpcContext getContext() {
        return LOCAL.get();
    }

    /**
     * remove context.
     *
     * @see com.alibaba.dubbo.rpc.filter.ContextFilter
     */
    public static void removeContext() {
        LOCAL.remove();
    }

    /**
     * Get the request object of the underlying RPC protocol, e.g. HttpServletRequest
     * 获取底层 RPC 协议的请求对象，例如 HttpServletRequest
     *
     * @return null if the underlying protocol doesn't provide support for getting request / 如果底层协议不支持获取请求，则为 null
     */
    public Object getRequest() {
        return request;
    }

    /**
     * Get the request object of the underlying RPC protocol, e.g. HttpServletRequest
     *
     * @return null if the underlying protocol doesn't provide support for getting request or the request is not of the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> T getRequest(Class<T> clazz) {
        return (request != null && clazz.isAssignableFrom(request.getClass())) ? (T) request : null;
    }


    public void setRequest(Object request) {
        this.request = request;
    }

    /**
     * Get the response object of the underlying RPC protocol, e.g. HttpServletResponse
     *
     * @return null if the underlying protocol doesn't provide support for getting response
     */
    public Object getResponse() {
        return response;
    }

    /**
     * Get the response object of the underlying RPC protocol, e.g. HttpServletResponse
     * <p>
     * 获取底层 RPC 协议的响应对象，例如HttpServletResponse
     * 如果底层协议不支持获取响应或响应不是指定类型，则返回 null
     *
     * @return null if the underlying protocol doesn't provide support for getting response or the response is not of the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> T getResponse(Class<T> clazz) {
        return (response != null && clazz.isAssignableFrom(response.getClass())) ? (T) response : null;
    }

    public void setResponse(Object response) {
        this.response = response;
    }

    /**
     * is provider side.
     * <p>
     * 是否是 服务提供方侧
     *
     * @return provider side.
     */
    public boolean isProviderSide() {
        return !isConsumerSide();
    }

    /**
     * is consumer side.
     * <p>
     * 是否是 服务消费方侧
     *
     * @return consumer side.
     */
    public boolean isConsumerSide() {
        return getUrl().getParameter(Constants.SIDE_KEY, Constants.PROVIDER_SIDE).equals(Constants.CONSUMER_SIDE);
    }

    /**
     * get future.
     *
     * @param <T>
     * @return future
     */
    @SuppressWarnings("unchecked")
    public <T> Future<T> getFuture() {
        return (Future<T>) future;
    }

    /**
     * set future.
     *
     * @param future
     */
    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public List<URL> getUrls() {
        return urls == null && url != null ? (List<URL>) Arrays.asList(url) : urls;
    }

    public void setUrls(List<URL> urls) {
        this.urls = urls;
    }

    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    /**
     * get method name.
     *
     * @return method name.
     */
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    /**
     * get parameter types.
     *
     * @serial
     */
    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(Class<?>[] parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    /**
     * get arguments.
     *
     * @return arguments.
     */
    public Object[] getArguments() {
        return arguments;
    }

    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    /**
     * set local address.
     *
     * @param host
     * @param port
     * @return context
     */
    public RpcContext setLocalAddress(String host, int port) {
        if (port < 0) {
            port = 0;
        }
        this.localAddress = InetSocketAddress.createUnresolved(host, port);
        return this;
    }

    /**
     * get local address.
     *
     * @return local address
     */
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    /**
     * set local address.
     *
     * @param address
     * @return context
     */
    public RpcContext setLocalAddress(InetSocketAddress address) {
        this.localAddress = address;
        return this;
    }

    public String getLocalAddressString() {
        return getLocalHost() + ":" + getLocalPort();
    }

    /**
     * get local host name.
     *
     * @return local host name
     */
    public String getLocalHostName() {
        String host = localAddress == null ? null : localAddress.getHostName();
        if (host == null || host.length() == 0) {
            return getLocalHost();
        }
        return host;
    }

    /**
     * set remote address.
     *
     * @param host
     * @param port
     * @return context
     */
    public RpcContext setRemoteAddress(String host, int port) {
        if (port < 0) {
            port = 0;
        }
        this.remoteAddress = InetSocketAddress.createUnresolved(host, port);
        return this;
    }

    /**
     * get remote address.
     *
     * @return remote address
     */
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * set remote address.
     *
     * @param address
     * @return context
     */
    public RpcContext setRemoteAddress(InetSocketAddress address) {
        this.remoteAddress = address;
        return this;
    }

    /**
     * get remote address string.
     *
     * @return remote address string.
     */
    public String getRemoteAddressString() {
        return getRemoteHost() + ":" + getRemotePort();
    }

    /**
     * get remote host name.
     *
     * @return remote host name
     */
    public String getRemoteHostName() {
        return remoteAddress == null ? null : remoteAddress.getHostName();
    }

    /**
     * get local host.
     *
     * @return local host
     */
    public String getLocalHost() {
        String host = localAddress == null ? null :
                localAddress.getAddress() == null ? localAddress.getHostName()
                        : NetUtils.filterLocalHost(localAddress.getAddress().getHostAddress());
        if (host == null || host.length() == 0) {
            return NetUtils.getLocalHost();
        }
        return host;
    }

    /**
     * get local port.
     *
     * @return port
     */
    public int getLocalPort() {
        return localAddress == null ? 0 : localAddress.getPort();
    }

    /**
     * get remote host.
     *
     * @return remote host
     */
    public String getRemoteHost() {
        return remoteAddress == null ? null :
                remoteAddress.getAddress() == null ? remoteAddress.getHostName()
                        : NetUtils.filterLocalHost(remoteAddress.getAddress().getHostAddress());
    }

    /**
     * get remote port.
     *
     * @return remote port
     */
    public int getRemotePort() {
        return remoteAddress == null ? 0 : remoteAddress.getPort();
    }

    /**
     * get attachment.
     *
     * @param key
     * @return attachment
     */
    public String getAttachment(String key) {
        return attachments.get(key);
    }

    /**
     * set attachment.
     *
     * @param key
     * @param value
     * @return context
     */
    public RpcContext setAttachment(String key, String value) {
        if (value == null) {
            attachments.remove(key);
        } else {
            attachments.put(key, value);
        }
        return this;
    }

    /**
     * remove attachment.
     *
     * @param key
     * @return context
     */
    public RpcContext removeAttachment(String key) {
        attachments.remove(key);
        return this;
    }

    /**
     * get attachments.
     *
     * @return attachments
     */
    public Map<String, String> getAttachments() {
        return attachments;
    }

    /**
     * set attachments
     *
     * @param attachment
     * @return context
     */
    public RpcContext setAttachments(Map<String, String> attachment) {
        this.attachments.clear();
        if (attachment != null && attachment.size() > 0) {
            this.attachments.putAll(attachment);
        }
        return this;
    }

    public void clearAttachments() {
        this.attachments.clear();
    }

    /**
     * get values.
     *
     * @return values
     */
    public Map<String, Object> get() {
        return values;
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     * @return context
     */
    public RpcContext set(String key, Object value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        return this;
    }

    /**
     * remove value.
     *
     * @param key
     * @return value
     */
    public RpcContext remove(String key) {
        values.remove(key);
        return this;
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * @deprecated Replace to isProviderSide()
     */
    @Deprecated
    public boolean isServerSide() {
        return isProviderSide();
    }

    /**
     * @deprecated Replace to isConsumerSide()
     */
    @Deprecated
    public boolean isClientSide() {
        return isConsumerSide();
    }

    /**
     * @deprecated Replace to getUrls()
     */
    @Deprecated
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Invoker<?>> getInvokers() {
        return invokers == null && invoker != null ? (List) Arrays.asList(invoker) : invokers;
    }

    public RpcContext setInvokers(List<Invoker<?>> invokers) {
        this.invokers = invokers;
        if (invokers != null && !invokers.isEmpty()) {
            List<URL> urls = new ArrayList<URL>(invokers.size());
            for (Invoker<?> invoker : invokers) {
                urls.add(invoker.getUrl());
            }
            setUrls(urls);
        }
        return this;
    }

    /**
     * @deprecated Replace to getUrl()
     */
    @Deprecated
    public Invoker<?> getInvoker() {
        return invoker;
    }

    public RpcContext setInvoker(Invoker<?> invoker) {
        this.invoker = invoker;
        if (invoker != null) {
            setUrl(invoker.getUrl());
        }
        return this;
    }

    /**
     * @deprecated Replace to getMethodName(), getParameterTypes(), getArguments()
     */
    @Deprecated
    public Invocation getInvocation() {
        return invocation;
    }

    public RpcContext setInvocation(Invocation invocation) {
        this.invocation = invocation;
        if (invocation != null) {
            setMethodName(invocation.getMethodName());
            setParameterTypes(invocation.getParameterTypes());
            setArguments(invocation.getArguments());
        }
        return this;
    }

    /**
     * Async invocation. Timeout will be handled even if <code>Future.get()</code> is not called.
     * 异步调用。即使未调用 <code>Future.get()<code> 也会处理超时。
     *
     * @param callable
     * @return get the return result from <code>future.get()</code>
     */
    @SuppressWarnings("unchecked")
    public <T> Future<T> asyncCall(Callable<T> callable) {
        try {
            try {
                setAttachment(Constants.ASYNC_KEY, Boolean.TRUE.toString());
                final T o = callable.call();
                //local invoke will return directly
                if (o != null) {
                    FutureTask<T> f = new FutureTask<T>(new Callable<T>() {
                        @Override
                        public T call() throws Exception {
                            return o;
                        }
                    });
                    f.run();
                    return f;
                } else {

                }
            } catch (Exception e) {
                throw new RpcException(e);
            } finally {
                removeAttachment(Constants.ASYNC_KEY);
            }
        } catch (final RpcException e) {
            return new Future<T>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public T get() throws InterruptedException, ExecutionException {
                    throw new ExecutionException(e.getCause());
                }

                @Override
                public T get(long timeout, TimeUnit unit)
                        throws InterruptedException, ExecutionException,
                        TimeoutException {
                    return get();
                }
            };
        }
        return ((Future<T>) getContext().getFuture());
    }

    /**
     * one way async call, send request only, and result is not required
     * <p>
     * 一种方式异步调用，只发送请求，不需要结果
     *
     * @param runnable
     */
    public void asyncCall(Runnable runnable) {
        try {
            setAttachment(Constants.RETURN_KEY, Boolean.FALSE.toString());
            runnable.run();
        } catch (Throwable e) {
            // FIXME should put exception in future?
            throw new RpcException("oneway call error ." + e.getMessage(), e);
        } finally {
            removeAttachment(Constants.RETURN_KEY);
        }
    }
}
