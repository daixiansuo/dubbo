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
package com.alibaba.dubbo.remoting.exchange;

import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;

/**
 * ExchangeChannel. (API/SPI, Prototype, ThreadSafe)
 */
public interface ExchangeChannel extends Channel {

    /**
     * send request.
     * 发送请求消息
     *
     * @param request
     * @return response future
     * @throws RemotingException
     */
    ResponseFuture request(Object request) throws RemotingException;

    /**
     * send request.
     * 发送请求消息，可以设置 超时时间
     *
     * @param request
     * @param timeout
     * @return response future
     * @throws RemotingException
     */
    ResponseFuture request(Object request, int timeout) throws RemotingException;

    /**
     * get message handler.
     * 获取 信息交换处理器
     *
     * @return message handler
     */
    ExchangeHandler getExchangeHandler();

    /**
     * graceful close.
     * 优雅关闭
     *
     * @param timeout
     */
    @Override
    void close(int timeout);

}
