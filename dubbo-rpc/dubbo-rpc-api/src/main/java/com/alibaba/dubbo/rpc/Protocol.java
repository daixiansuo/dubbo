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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 */
@SPI("dubbo")
public interface Protocol {

    /**
     * Get default port when user doesn't config the port.
     * 当用户没有配置协议暴露端口时， 则获取默认端口
     *
     * @return default port
     */
    int getDefaultPort();

    /**
     * Export service for remote invocation: <br>
     * 1. Protocol should record request source address after receive a request:
     * RpcContext.getContext().setRemoteAddress();<br>
     * 2. export() must be idempotent, that is, there's no difference between invoking once and invoking twice when
     * export the same URL<br>
     * 3. Invoker instance is passed in by the framework, protocol needs not to care <br>
     * <p>
     * <p>
     * 1. 协议收到请求后应记录请求源地址：RpcContext.getContext().setRemoteAddress()
     * 2. export() 服务导出（暴露），必须是幂等的，即导出同一个URL时调用一次和调用两次没有区别。
     * 3. Invoker 实例由框架传入，协议不需要关心
     * <p>
     * 服务暴露
     *
     * @param <T>     Service type
     * @param invoker Service invoker
     * @return exporter reference for exported service, useful for unexport the service later
     * @throws RpcException thrown when error occurs during export the service, for example: port is occupied
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * Refer a remote service: <br>
     * 1. When user calls `invoke()` method of `Invoker` object which's returned from `refer()` call, the protocol
     * needs to correspondingly execute `invoke()` method of `Invoker` object <br>
     * 2. It's protocol's responsibility to implement `Invoker` which's returned from `refer()`. Generally speaking,
     * protocol sends remote request in the `Invoker` implementation. <br>
     * 3. When there's check=false set in URL, the implementation must not throw exception but try to recover when
     * connection fails.
     * <p>
     * 引用远程服务：
     * <p>
     * 1. 当用户调用 `refer()` 调用返回的 `Invoker` 对象的 `invoke()` 方法时，协议需要相应执行 `Invoker` 对象的 `invoke()` 方法
     * 2. 协议负责实现从 `refer()` 返回的`Invoker`。一般来说，协议在 `Invoker` 实现中发送远程请求。
     * 3. 当 URL 中设置了 check=false 时，实现不能抛出异常，而是在连接失败时尝试恢复。
     *
     *
     * <p>
     * 引用服务方法
     *
     * @param <T>  Service type
     * @param type Service class
     * @param url  URL address for the remote service  /  远程服务的 URL 地址
     * @return invoker service's local proxy
     * @throws RpcException when there's any error while connecting to the service provider
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * Destroy protocol: <br>
     * 1. Cancel all services this protocol exports and refers <br>
     * 2. Release all occupied resources, for example: connection, port, etc. <br>
     * 3. Protocol can continue to export and refer new service even after it's destroyed.
     *
     * 销毁协议：
     * 1. 取消此协议导出和引用的所有服务
     * 2. 释放所有占用的资源，例如：连接、端口等。
     * 3. 协议即使在销毁后也可以继续导出和引用新服务。
     */
    void destroy();

}