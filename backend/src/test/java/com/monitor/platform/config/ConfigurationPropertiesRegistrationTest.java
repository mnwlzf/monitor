package com.monitor.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住一个「本地全绿、线上起不来」的坑。
 *
 * <p>{@code @ConfigurationProperties} 只是声明，必须再有一个
 * {@code @Configuration @EnableConfigurationProperties(X.class)} 才会成为 Bean。
 * 少了这一步，依赖它的 {@code @Component} 会在启动时注入失败 ——
 * 而普通单测基本是 mock / standalone 的，不会启动 Spring 上下文，压根发现不了。</p>
 *
 * <p>线上真实案例：新增 {@code ImageGenProperties} 时忘了配对的配置类，
 * CI 全绿、镜像也构建成功，容器起来后 crash-loop，把服务器 CPU 打满。</p>
 */
class ConfigurationPropertiesRegistrationTest {

    private static final String BASE_PACKAGE = "com.monitor.platform";

    @Test
    void everyConfigurationPropertiesClassShouldBeRegisteredAsBean() throws Exception {
        Set<String> declared = scan(ConfigurationProperties.class);
        assertTrue(!declared.isEmpty(), "没扫到任何 @ConfigurationProperties，扫描路径可能写错了");

        Set<String> registered = new HashSet<>();
        for (String className : scan(EnableConfigurationProperties.class)) {
            Class<?> configClass = Class.forName(className);
            EnableConfigurationProperties annotation =
                    configClass.getAnnotation(EnableConfigurationProperties.class);
            if (annotation == null) {
                continue;
            }
            for (Class<?> type : annotation.value()) {
                registered.add(type.getName());
            }
        }

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(registered);
        assertTrue(missing.isEmpty(),
                "这些 @ConfigurationProperties 没有被任何 @EnableConfigurationProperties 注册，"
                        + "应用启动时会因注入失败而起不来：" + missing);
    }

    private static Set<String> scan(Class<? extends Annotation> annotation) throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // @ConfigurationProperties 常放在普通类上，不一定是 @Component
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        Set<String> names = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            names.add(definition.getBeanClassName());
        }
        return names;
    }
}
