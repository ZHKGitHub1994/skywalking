/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.agent.core.plugin.loader;

import org.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.skywalking.apm.agent.core.boot.AgentPackagePath;
import org.skywalking.apm.agent.core.plugin.PluginBootstrap;
import org.skywalking.apm.agent.core.logging.api.ILog;
import org.skywalking.apm.agent.core.logging.api.LogManager;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The <code>AgentClassLoader</code> represents a classloader,
 * which is in charge of finding plugins and interceptors.
 *
 * @author wusheng
 */
public class AgentClassLoader extends ClassLoader {

    private static final ILog logger = LogManager.getLogger(AgentClassLoader.class);
    /**
     * The default class loader for the agent.
     */
    private static AgentClassLoader DEFAULT_LOADER;

    /**
     * classpath
     */
    private List<File> classpath;
    /**
     * Jar 信息数组
     */
    private List<Jar> allJars;
    /**
     * Jar 读取时的锁
     */
    private ReentrantLock jarScanLock = new ReentrantLock();

    public static AgentClassLoader getDefault() {
        return DEFAULT_LOADER;
    }

    /**
     * Init the default
     *
     * @return
     * @throws AgentPackageNotFoundException
     */
    public static AgentClassLoader initDefaultLoader() throws AgentPackageNotFoundException {
        DEFAULT_LOADER = new AgentClassLoader(PluginBootstrap.class.getClassLoader());
        return getDefault();
    }

    public AgentClassLoader(ClassLoader parent) throws AgentPackageNotFoundException {
        super(parent);
        File agentDictionary = AgentPackagePath.getPath();
        classpath = new LinkedList<File>();
        classpath.add(new File(agentDictionary, "plugins"));
        classpath.add(new File(agentDictionary, "activations"));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        List<Jar> allJars = getAllJars();
        String path = name.replace('.', '/').concat(".class");
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(path);
            if (entry != null) {
                try {
                    URL classFileUrl = new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + path);
                    byte[] data = null;
                    BufferedInputStream is = null;
                    ByteArrayOutputStream baos = null;
                    try {
                        is = new BufferedInputStream(classFileUrl.openStream());
                        baos = new ByteArrayOutputStream();
                        int ch = 0;
                        while ((ch = is.read()) != -1) {
                            baos.write(ch);
                        }
                        data = baos.toByteArray();
                    } finally {
                        if (is != null)
                            try {
                                is.close();
                            } catch (IOException ignored) {
                            }
                        if (baos != null)
                            try {
                                baos.close();
                            } catch (IOException ignored) {
                            }
                    }
                    return defineClass(name, data, 0, data.length);
                } catch (MalformedURLException e) {
                    logger.error(e, "find class fail.");
                } catch (IOException e) {
                    logger.error(e, "find class fail.");
                }
            }
        }
        throw new ClassNotFoundException("Can't find " + name);
    }

    @Override
    protected URL findResource(String name) {
        // 获得 Jar 信息数组
        List<Jar> allJars = getAllJars();
        // 遍历 Jar 信息数组，获得资源( 例如，Class )的路径
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(name);
            if (entry != null) {
                try {
                    return new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + name);
                } catch (MalformedURLException e) {
                    continue;
                }
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        List<URL> allResources = new LinkedList<URL>();
        // 获得 Jar 信息数组
        List<Jar> allJars = getAllJars();
        // 遍历 Jar 信息数组，获得资源( 例如，Class )的路径
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(name);
            if (entry != null) {
                allResources.add(new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + name));
            }
        }

        // 返回迭代器
        final Iterator<URL> iterator = allResources.iterator();
        return new Enumeration<URL>() {
            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            @Override
            public URL nextElement() {
                return iterator.next();
            }
        };
    }

    /**
     * 从 classpath 加载所有 Jar 信息
     *
     * @return Jar 信息数组
     */
    private List<Jar> getAllJars() {
        if (allJars == null) {
            jarScanLock.lock(); // 保证并发下，不重复读取
            try {
                if (allJars == null) {
                    allJars = new LinkedList<Jar>();
                    // 遍历 classpath
                    for (File path : classpath) {
                        if (path.exists() && path.isDirectory()) {
                            // 获得所有 Jar 的文件名
                            String[] jarFileNames = path.list(new FilenameFilter() {
                                @Override
                                public boolean accept(File dir, String name) {
                                    return name.endsWith(".jar");
                                }
                            });
                            // 获得所有 Jar
                            for (String fileName : jarFileNames) {
                                try {
                                    File file = new File(path, fileName);
                                    Jar jar = new Jar(new JarFile(file), file);
                                    allJars.add(jar);
                                    logger.info("{} loaded.", file.toString());
                                } catch (IOException e) {
                                    logger.error(e, "{} jar file can't be resolved", fileName);
                                }
                            }
                        }
                    }
                }
            } finally {
                jarScanLock.unlock();
            }
        }

        return allJars;
    }

    /**
     * Jar 信息
     */
    private class Jar {
        /**
         * Jar 文件，用于查找 Jar 里的类
         */
        private JarFile jarFile;
        /**
         * Jar 文件
         */
        private File sourceFile;

        private Jar(JarFile jarFile, File sourceFile) {
            this.jarFile = jarFile;
            this.sourceFile = sourceFile;
        }
    }
}
