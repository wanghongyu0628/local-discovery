package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.List;

/**
 * 拦截 BaseLoadBalancer.chooseServer(Object) —— Netflix Ribbon 服务选择的唯一汇聚点
 *
 * 覆盖 Spring Cloud Hoxton（2020.0 之前）的调用路径：
 *   Feign:        LoadBalancerFeignClient → FeignLoadBalancer → LoadBalancerCommand
 *                 → LoadBalancerContext.getServerFromLoadBalancer() → ILoadBalancer.chooseServer()
 *   RestTemplate: RibbonLoadBalancerClient.execute() → ILoadBalancer.chooseServer()
 *
 * ILoadBalancer 的默认实现是 ZoneAwareLoadBalancer，单 zone 时委托父类 BaseLoadBalancer，
 * 因此拦截 BaseLoadBalancer.chooseServer 即可覆盖全部 Ribbon 路径。
 *
 * 命中本地注册中心 → 反射构造 com.netflix.loadbalancer.Server 返回，跳过原方法
 * 未命中 → 放行原方法（Ribbon 规则从 Nacos/Eureka 服务列表选择，实现远端穿透）
 *
 * 注意：Server 的反射构造必须写在 Advice 内联代码中执行，
 * 这样 Class.forName 才会用宿主应用的 ClassLoader 解析（与 DefaultServiceInstance 的处理一致）。
 */
public class RibbonLoadBalancerInterceptor {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.This Object self) {
        try {
            // BaseLoadBalancer.getName() = ribbon client 名 = 服务名（@FeignClient 的 name / RestTemplate 的 host）
            Object name = self.getClass().getMethod("getName").invoke(self);
            if (!(name instanceof String)) return null;
            String lookupId = (String) name;
            // Nacos 带分组前缀的客户端名，如 "CLOUD_GROUP@@service-a"
            if (lookupId.contains("@@")) {
                lookupId = lookupId.substring(lookupId.indexOf("@@") + 2);
            }

            List<String[]> instances = DiscoveryBridge.lookup(lookupId);
            if (instances != null && !instances.isEmpty()) {
                // 取第一个实例（与 BlockingLoadBalancerClient 路径行为一致）
                String[] inst = instances.get(0);
                String host = inst[0];
                int port = Integer.parseInt(inst[1]);

                // 反射构造 com.netflix.loadbalancer.Server(host, port)
                Class<?> serverClass = Class.forName("com.netflix.loadbalancer.Server");
                Object server = serverClass.getConstructor(String.class, int.class)
                        .newInstance(host, port);
                try {
                    serverClass.getMethod("setAlive", boolean.class).invoke(server, Boolean.TRUE);
                    serverClass.getMethod("setReadyToServe", boolean.class).invoke(server, Boolean.TRUE);
                } catch (Exception ignore) {
                    // 部分版本无此方法，不影响使用
                }
                System.out.println("[LocalDiscovery] " + lookupId + " → 本地 " + host + ":" + port);
                return server;
            }
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] Ribbon 拦截异常: " + e.getMessage());
        }
        return null;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object intercepted,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
        if (intercepted != null) {
            result = intercepted;
        }
    }
}
