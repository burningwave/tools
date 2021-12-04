/*
 * This file is part of Burningwave Tools.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/tools
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2021 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.tools.dependencies;

import static org.burningwave.core.assembler.StaticComponentContainer.BackgroundExecutor;
import static org.burningwave.core.assembler.StaticComponentContainer.ClassLoaders;
import static org.burningwave.core.assembler.StaticComponentContainer.Classes;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggerRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.concurrent.QueuedTaskExecutor.Task;
import org.burningwave.core.function.ThrowingBiFunction;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemItem;

public class Sniffer extends MemoryClassLoader {
	private Function<JavaClass, Boolean> javaClassFilterAndAdder;
	private Function<FileSystemItem, Boolean> resourceFilterAndAdder;
	private Map<String, String> resources;
	// In this map the key is the absolute path
	private Map<String, JavaClass> javaClasses;
	// In this map the key is the class name
	private Map<String, JavaClass> bwJavaClasses;
	private TriConsumer<String, String, ByteBuffer> resourcesConsumer;
	ClassLoader threadContextClassLoader;
	Function<Boolean, ClassLoader> masterClassLoaderRetrieverAndResetter;
	ThrowingBiFunction<String, Boolean, Class<?>, ClassNotFoundException> classLoadingFunction;
	private Collection<Task> tasksInExecution;

	public Sniffer(ClassLoader parent) {
		super(parent);
	}

	static {
		ClassLoader.registerAsParallelCapable();
	}

	protected Sniffer init(boolean useAsMasterClassLoader, Collection<String> baseClassPaths,
			Function<JavaClass, Boolean> javaClassAdder, Function<FileSystemItem, Boolean> resourceAdder,
			TriConsumer<String, String, ByteBuffer> resourcesConsumer) {
		this.threadContextClassLoader = Thread.currentThread().getContextClassLoader();
		this.javaClassFilterAndAdder = javaClassAdder;
		this.resourceFilterAndAdder = resourceAdder;
		this.resourcesConsumer = resourcesConsumer;
		this.tasksInExecution = ConcurrentHashMap.newKeySet();
		initResourceLoader(baseClassPaths);
		if (useAsMasterClassLoader) {
			// Load in cache defineClass and definePackage methods for
			// threadContextClassLoader
			ClassLoaders.getDefineClassMethod(threadContextClassLoader);
			ClassLoaders.getDefinePackageMethod(threadContextClassLoader);
			classLoadingFunction = (className, resolve) -> {
				if ((!className.startsWith("org.burningwave.") && !className.startsWith("io.github.toolfactory."))) {
					return super.loadClass(className, resolve);
				} else {
					JavaClass javaClass = bwJavaClasses.get(className);
					try {
						return ClassLoaders.defineOrLoad(threadContextClassLoader, javaClass);
					} catch (NoClassDefFoundError | ReflectiveOperationException exc) {
						throw new ClassNotFoundException(Classes.retrieveName(exc));
					} catch (NullPointerException exc) {
						if (javaClass == null) {
							throw new ClassNotFoundException(className);
						}
						throw exc;
					}
				}
			};
			masterClassLoaderRetrieverAndResetter = ClassLoaders.setAsParent(threadContextClassLoader, this);
		} else {
			classLoadingFunction = (clsName, resolveFlag) -> {
				return super.loadClass(clsName, resolveFlag);
			};
			Thread.currentThread().setContextClassLoader(this);
		}

		return this;
	}

	@Override
	public synchronized void addByteCode(String className, ByteBuffer byteCode) {
		super.addByteCode(className, byteCode);
	}

	private void initResourceLoader(Collection<String> baseClassPaths) {
		this.resources = new ConcurrentHashMap<>();
		this.javaClasses = new ConcurrentHashMap<>();
		this.bwJavaClasses = new ConcurrentHashMap<>();
		ManagedLoggerRepository.logInfo(getClass()::getName, "Scanning paths :\n{}",
				String.join("\n", baseClassPaths));
		for (String classPath : baseClassPaths) {
			FileSystemItem.ofPath(classPath).refresh()
			.findInAllChildren(FileSystemItem.Criteria.forAllFileThat((fileSystemItem) -> {
				String absolutePath = fileSystemItem.getAbsolutePath();
				resources.put(absolutePath, classPath);
				JavaClass javaClass = fileSystemItem.toJavaClass();
				if (javaClass != null) {
					addByteCode(javaClass.getName(), javaClass.getByteCode());
					javaClasses.put(absolutePath, javaClass);
					if (javaClass.getName().startsWith("org.burningwave.")
							|| javaClass.getName().startsWith("io.github.toolfactory.")) {
						bwJavaClasses.put(javaClass.getName(), javaClass);
					}
				}
				return true;
			}));
		}
	}

	protected void consumeClass(String className) {
		consumeClasses(Arrays.asList(className));
	}

	public void consumeClasses(Collection<String> currentNotFoundClasses) {
		for (Map.Entry<String, JavaClass> entry : javaClasses.entrySet()) {
			if (currentNotFoundClasses.contains(entry.getValue().getName())) {
				JavaClass javaClass = entry.getValue();
				if (javaClassFilterAndAdder.apply(javaClass)) {
					Task tsk = BackgroundExecutor.createTask(task -> {
						try {
							resourcesConsumer.accept(entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
						} catch (Throwable exc) {
							try {
								FileSystemItem classPath = FileSystemItem.ofPath(resources.get(entry.getKey()));
								FileSystemItem javaClassFIS = FileSystemItem.ofPath(entry.getKey());
								String itemRelativePath = javaClassFIS.getAbsolutePath().substring(classPath.getAbsolutePath().length() + 1);
								resourcesConsumer.accept(entry.getKey(), itemRelativePath, javaClass.getByteCode());
							} catch (Throwable exception) {
								throw exc;
							}
						}
						tasksInExecution.remove(task);
					});
					tasksInExecution.add(tsk);
					tsk.submit();
				}
			}
		}
	}

	protected Collection<FileSystemItem> consumeResource(String relativePath, boolean breakWhenFound) {
		Set<FileSystemItem> founds = new LinkedHashSet<>();
		if (Strings.isNotEmpty(relativePath)) {
			for (Map.Entry<String, String> entry : resources.entrySet()) {
				if (entry.getKey().endsWith(relativePath)) {
					FileSystemItem fileSystemItem = FileSystemItem.ofPath(entry.getKey());
					founds.add(fileSystemItem);
					if (resourceFilterAndAdder.apply(fileSystemItem)) {
						Task tsk = BackgroundExecutor.createTask(task -> {
							resourcesConsumer.accept(entry.getKey(), relativePath, fileSystemItem.toByteBuffer());
							tasksInExecution.remove(task);
						});
						tasksInExecution.add(tsk);
						tsk.submit();
					}
					if (breakWhenFound) {
						break;
					}
				}
			}
		}
		return founds;
	}

	@Override
	public void addLoadedByteCode(String className, ByteBuffer byteCode) {
		super.addLoadedByteCode(className, byteCode);
		consumeClass(className);
	}

	@Override
	protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
		Class<?> cls = classLoadingFunction.apply(className, resolve);
		consumeClass(className);
		return cls;
	}

	public Class<?> _loadClass(String className, boolean resolve) throws ClassNotFoundException {
		Class<?> cls = classLoadingFunction.apply(className, resolve);
		consumeClass(className);
		return cls;
	}

	@Override
	public URL getResource(String name) {
		Enumeration<URL> urls = getResources(name, true);
		if (urls.hasMoreElements()) {
			return urls.nextElement();
		}
		return null;
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		return getResources(name, false);
	}

	private Enumeration<URL> getResources(String name, boolean findFirst) {
		return Collections.enumeration(consumeResource(name, findFirst).stream().map(fileSystemItem -> {
			resourceFilterAndAdder.apply(fileSystemItem);
			return fileSystemItem.getURL();
		}).collect(Collectors.toSet()));
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		FileSystemItem fileSystemItem = consumeResource(name, true).stream().findFirst().orElseGet(() -> null);
		if (fileSystemItem != null) {
			return fileSystemItem.toInputStream();
		} else {
			return getByteCodeAsInputStream(name);
		}
	}

	@Override
	public void close() {
		closeResources(() -> tasksInExecution == null, task -> {
			if (!tasksInExecution.isEmpty()) {
				tasksInExecution.stream().forEach(Task::waitForFinish);
				tasksInExecution = null;
			}
			if (threadContextClassLoader != null) {
				Thread.currentThread().setContextClassLoader(threadContextClassLoader);
			}
			if (masterClassLoaderRetrieverAndResetter != null) {
				masterClassLoaderRetrieverAndResetter.apply(true);
				masterClassLoaderRetrieverAndResetter = null;
			}
			resources.clear();
			// Nulling resources will cause crash
			// resources = null;
			javaClasses.clear();
			// Nulling javaClasses will cause crash
			// javaClasses = null;
//	    	javaClassFilterAndAdder = null;
//	    	resourceFilterAndAdder = null;
//	    	threadContextClassLoader = null;
//	    	classLoadingFunction = null;
//	    	clear();
			unregister();
		});
	}
}
