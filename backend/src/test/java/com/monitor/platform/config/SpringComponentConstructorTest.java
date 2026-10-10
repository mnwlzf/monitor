package com.monitor.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 组件的构造器必须无歧义。
 *
 * <p>背景：{@code WeatherTools} 为了可测试加了一个注入 {@code RestClient} 的包可见构造器，
 * 结果类里出现两个构造器且都没有 {@code @Autowired} —— Spring 会退回去找无参构造器，
 * 找不到就启动失败：{@code No default constructor found}。</p>
 *
 * <p>这类问题编译能过、单测也全绿（项目里唯一的 {@code @SpringBootTest} 是连真实库的
 * 联调用例，默认被排除），只有部署起来才炸。所以这里做一次静态检查：
 * <strong>组件类只要有多个构造器，就必须有一个标了 {@code @Autowired}</strong>。</p>
 */
class SpringComponentConstructorTest {

    private static final String BASE_PACKAGE = "com.monitor.platform";

    @Test
    void springComponentsMustHaveUnambiguousConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // @Service / @Repository / @Configuration / @RestController 都元注解了 @Component
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            checked++;
            boolean annotated = false;
            for (Constructor<?> constructor : constructors) {
                if (constructor.isAnnotationPresent(Autowired.class)) {
                    annotated = true;
                    break;
                }
            }
            if (!annotated) {
                problems.add(String.format("%s 有 %d 个构造器，但没有一个标 @Autowired —— Spring 无法确定用哪个",
                        type.getName(), constructors.length));
            }
        }

        assertTrue(problems.isEmpty(),
                "Spring 组件构造器有歧义，应用会在启动时报 No default constructor found：\n  "
                        + String.join("\n  ", problems));
        // 至少要扫到一些多构造器的类，否则说明扫描根本没生效
        assertTrue(checked > 0, "没有扫到任何多构造器的组件，检查扫描配置是否生效");
    }
}