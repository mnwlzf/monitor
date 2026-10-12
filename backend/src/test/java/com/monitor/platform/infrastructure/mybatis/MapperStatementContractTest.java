package com.monitor.platform.infrastructure.mybatis;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper 接口与 XML 的绑定契约：接口里每个方法都要能在 XML 里找到同名语句。
 *
 * <p>踩过一次：改查询 join 时把 {@code <insert id="insertEvent">} 整段删掉，
 * 编译和当时的单测都发现不了，直到线上写变更事件才抛
 * {@code Invalid bound statement (not found)}。这条用例把该检查固化下来。</p>
 *
 * <p>只覆盖手写 XML 的 Mapper：本项目所有 Mapper 都是接口 + XML，
 * 没有用 {@code @Select} 之类的注解，也没有继承 MyBatis-Plus 的 BaseMapper。</p>
 */
class MapperStatementContractTest {

    @Test
    void everyMapperMethodShouldBeBoundToStatement() throws Exception {
        Configuration configuration = new Configuration();
        Resource[] xmlFiles = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/**/*.xml");
        assertFalse(xmlFiles.length == 0, "没有找到任何 Mapper XML");
        for (Resource resource : xmlFiles) {
            try (InputStream inputStream = resource.getInputStream()) {
                new XMLMapperBuilder(inputStream, configuration, resource.getFilename(),
                        configuration.getSqlFragments()).parse();
            }
        }

        List<Class<?>> mappers = mapperInterfaces();
        assertFalse(mappers.isEmpty(), "没有找到任何 Mapper 接口");

        List<String> missing = new ArrayList<>();
        for (Class<?> mapper : mappers) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!configuration.hasStatement(mapper.getName() + "." + method.getName())) {
                    missing.add(mapper.getName() + "." + method.getName());
                }
            }
        }
        assertTrue(missing.isEmpty(), "这些 Mapper 方法没有对应的 SQL 语句: " + missing);
    }

    private static List<Class<?>> mapperInterfaces() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/monitor/platform/**/*Mapper.class");
        List<Class<?>> result = new ArrayList<>();
        for (Resource resource : resources) {
            String path = resource.getURL().getPath();
            int index = path.indexOf("com/monitor/platform/");
            if (index < 0) {
                continue;
            }
            String className = path.substring(index, path.length() - ".class".length())
                    .replace('/', '.');
            Class<?> type = Class.forName(className);
            if (type.isInterface()) {
                result.add(type);
            }
        }
        return result;
    }
}