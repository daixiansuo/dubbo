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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Resetable;

/**
 * Remoting Client. (API/SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 * <p>
 * Client 继承 Endpoint， 是因为抽象出的公共方法。
 * Client 继承 Channel，是因为 Channel 与 Client 是一一对应的，所以如此设计。
 * Client 继承 Resetable，是因为 重置功能 需要继承统一接口，但是已经不推荐使用。
 *
 * @see com.alibaba.dubbo.remoting.Transporter#connect(com.alibaba.dubbo.common.URL, ChannelHandler)
 */
public interface Client extends Endpoint, Channel, Resetable {

    /**
     * reconnect.
     * 重新连接
     */
    void reconnect() throws RemotingException;

    /**
     * 重置
     *
     * @param parameters
     */
    @Deprecated
    void reset(com.alibaba.dubbo.common.Parameters parameters);

}