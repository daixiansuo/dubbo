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
package com.alibaba.dubbo.remoting.telnet.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.telnet.TelnetHandler;
import com.alibaba.dubbo.remoting.transport.ChannelHandlerAdapter;

public class TelnetHandlerAdapter extends ChannelHandlerAdapter implements TelnetHandler {

    private final ExtensionLoader<TelnetHandler> extensionLoader = ExtensionLoader.getExtensionLoader(TelnetHandler.class);

    @Override
    public String telnet(Channel channel, String message) throws RemotingException {
        String prompt = channel.getUrl().getParameterAndDecoded(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
        boolean noprompt = message.contains("--no-prompt");
        message = message.replace("--no-prompt", "");
        StringBuilder buf = new StringBuilder();
        message = message.trim();
        String command;
        if (message.length() > 0) {
            int i = message.indexOf(' ');
            if (i > 0) {
                // 提取执行命令
                command = message.substring(0, i).trim();
                // 提取命令后的所有字符串
                message = message.substring(i + 1).trim();
            } else {
                command = message;
                message = "";
            }
        } else {
            command = "";
        }
        if (command.length() > 0) {
            // 检查系统是否有命令对应的扩展点
            if (extensionLoader.hasExtension(command)) {
                if (commandEnabled(channel.getUrl(), command)) {
                    try {
                        // 获取对应扩展点 并且执行对应处理
                        String result = extensionLoader.getExtension(command).telnet(channel, message);
                        if (result == null) {
                            return null;
                        }
                        buf.append(result);
                    } catch (Throwable t) {
                        buf.append(t.getMessage());
                    }
                } else {
                    buf.append("Command: ");
                    buf.append(command);
                    buf.append(" disabled");
                }
            } else {
                buf.append("Unsupported command: ");
                buf.append(command);
            }
        }
        // 在 telnet 消息结尾追加 回车和换行
        if (buf.length() > 0) {
            buf.append("\r\n");
        }
        if (prompt != null && prompt.length() > 0 && !noprompt) {
            buf.append(prompt);
        }
        return buf.toString();
    }

    private boolean commandEnabled(URL url, String command) {
        boolean commandEnable = false;
        String supportCommands = url.getParameter(Constants.TELNET);
        if (StringUtils.isEmpty(supportCommands)) {
            commandEnable = true;
        } else {
            String[] commands = Constants.COMMA_SPLIT_PATTERN.split(supportCommands);
            for (String c : commands) {
                if (command.equals(c)) {
                    commandEnable = true;
                    break;
                }
            }
        }
        return commandEnable;
    }

}
