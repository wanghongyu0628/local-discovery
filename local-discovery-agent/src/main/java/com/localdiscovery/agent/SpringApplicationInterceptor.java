package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 SpringApplication.run() 的返回
 *
 * Spring 启动完成后提取 spring.application.name 和 server.port → 触发自动注册
 * DiscoveryClient 拦截由 DiscoveryClientInterceptor 在字节码层面完成，无需在此注入代理
 *
 * 注意：Spring Cloud Hoxton 及之前（bootstrap 机制默认开启）会先创建一个 bootstrap 父上下文，
 * 它同样通过 SpringApplication.run() 启动，但没有应用名也没有 Web 服务器。
 * 因此必须识别出真正携带 Web 服务器的应用上下文，否则 registered 标志会被 bootstrap
 * 上下文提前消费，导致真正的应用上下文永远不会注册。
 */
public class SpringApplicationInterceptor {

    public static volatile boolean registered = false;

    @Advice.OnMethodExit
    public static void onRun(@Advice.Return Object applicationContext) {
        if (applicationContext == null || registered) return;

        try {
            // 只有暴露 getWebServer() 的上下文（WebServerApplicationContext）才是真正的应用上下文；
            // bootstrap 父上下文是纯 AnnotationConfigApplicationContext，直接跳过，不消费 registered
            applicationContext.getClass().getMethod("getWebServer");
            extractAndRegister(applicationContext);
        } catch (NoSuchMethodException notWebServerContext) {
            // bootstrap / 父上下文等非 Web 上下文：等待真正的应用上下文
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] Spring 启动后处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 提取 spring.application.name 和 server.port，触发自动注册
     */
    public static void extractAndRegister(Object applicationContext) throws Exception {
        var getEnvMethod = applicationContext.getClass().getMethod("getEnvironment");
        Object env = getEnvMethod.invoke(applicationContext);
        var getPropertyMethod = env.getClass().getMethod("getProperty", String.class);

        String appName = (String) getPropertyMethod.invoke(env, "spring.application.name");
        String portStr = (String) getPropertyMethod.invoke(env, "local.server.port");
        if (portStr == null) {
            portStr = (String) getPropertyMethod.invoke(env, "server.port");
        }
        if (portStr == null) portStr = "8080";

        if (appName != null && !appName.isBlank()) {
            registered = true;
            EnvironmentBridge.applicationReady(new String[]{appName, portStr});
        }
    }
}
