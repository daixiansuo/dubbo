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
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;
import com.alibaba.dubbo.demo.MoneyService;
import com.alibaba.dubbo.demo.UserService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Consumer {

    public static void main(String[] args) {
        //Prevent to get IPV6 address,this way only work in debug mode
        //But you can pass use -Djava.net.preferIPv4Stack=true,then it work well whether in debug mode or not
        System.setProperty("java.net.preferIPv4Stack", "true");
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer.xml"});
        context.start();


        UserService userService = (UserService) context.getBean("userService"); // get remote service proxy
        String info = userService.info("test"); // call remote method
        System.out.println(info); // get result


        DemoService demoService = (DemoService) context.getBean("demoService"); // get remote service proxy
        String hello = demoService.sayHello("world"); // call remote method
        System.out.println(hello); // get result



        MoneyService moneyService = (MoneyService) context.getBean("moneyService"); // get remote service proxy
        String money = moneyService.money("100"); // call remote method
        System.out.println(money); // get result
    }
}
